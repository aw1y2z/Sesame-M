package io.github.aw1y2z.sesame.hook;

import io.github.aw1y2z.sesame.util.Log;
import fi.iki.elonen.NanoHTTPD;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP服务器类（基于NanoHTTPD）
 * 对应原Kotlin的ModuleHttpServer，保留所有路由注册和请求处理逻辑
 */
public class ModuleHttpServer extends NanoHTTPD {
    /** 请求体上限：本机接口最大的请求是 RPC 参数，1 MB 足够；超过直接拒读，避免按声明的长度分配内存 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final String tag = "ModuleHttpServer";
    private final Map<String, HttpHandler> routes = new HashMap<>();
    
    /**
     * 无参构造方法（等效Kotlin默认参数：port=8080，secretToken=""）
     */
    public ModuleHttpServer() {
        this(8080, "");
    }
    
    /**
     * 仅指定端口的构造方法（等效Kotlin：secretToken=""）
     * @param port 服务器端口
     */
    public ModuleHttpServer(int port) {
        this(port, "");
    }
    
    /**
     * 全参构造方法（对应Kotlin主构造器）
     * @param port 服务器端口
     * @param secretToken 秘钥令牌
     */
    public ModuleHttpServer(int port, String secretToken) {
        // 仅监听回环地址：避免同局域网内其他设备直接访问（原来绑 0.0.0.0 时，
        // /getAlipayMiniMark 与 /getAuthCode 两条路由不做鉴权，可被同网段任意设备调用）。
        // 本机调用不受影响；需要从电脑访问时用 adb forward tcp:8080 tcp:8080。
        super("127.0.0.1", port);
        // 原Kotlin init块中的路由注册逻辑
        register("/debugHandler", new DebugHandler(secretToken), "调试接口");
        // 这两条路由的 handler 不继承 BaseHandler，需把令牌传入、由其在入口自行校验
        register("/getAlipayMiniMark", new AlipayMiniMarkHandler(secretToken), "获取支付宝小程序标记");
        // 该路由依赖 AuthCodeHelper：它自建 Oauth2AuthCodeServiceImpl 实例、没走宿主依赖注入，
        // 实例内的 Oauth2AuthCodeFacade 恒为 null，调用必然抛 NPE（当前支付宝版本下固定返回 500）。
        // 保留路由本身，待改为「捕获宿主已注入的真实实例」后即可恢复；调用只在有人请求时才发生，闲置无日志。
        register("/getAuthCode", new AuthCodeHandler(secretToken), "获取OAuth2授权码（当前宿主版本下不可用）");
    }
    
    /**
     * 路由注册方法（默认description为空）
     * 对应Kotlin的@Suppress("SameParameterValue") + 默认参数
     * @param path 接口路径
     * @param handler 处理器
     */
    private void register(String path, HttpHandler handler) {
        register(path, handler, "");
    }
    
    /**
     * 核心路由注册方法
     * @param path 接口路径
     * @param handler 处理器
     * @param description 接口描述
     */
    private void register(String path, HttpHandler handler, String description) {
        Log.i("Registering handler : " + path + " -> " + description);
        routes.put(path, handler);
    }
    
    /**
     * 重写NanoHTTPD的请求处理方法
     * 核心逻辑：匹配路由 -> 读取请求体 -> 调用处理器 -> 全局异常捕获
     */
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        // 匹配路由，无匹配则返回404
        HttpHandler handler = routes.get(uri);
        if (handler == null) {
            // 404 只进调试日志：本机任意应用扫描回环端口就会产生大量 404，
            // 写进运行日志会刷爆文件，而且它不构成「有人拿错令牌敲门」这类审计信号
            Log.i("http 404 " + uri);
            return notFound();
        }

        long startAt = System.currentTimeMillis();
        Response response;
        try {
            // 仅POST/PUT请求读取Body
            String body = null;
            Method requestMethod = session.getMethod();
            if (requestMethod == Method.POST || requestMethod == Method.PUT) {
                body = getPostBodySafe(session);
            }
            // 调用处理器处理请求
            response = handler.handle(session, body);
        } catch (Throwable e) {
            // 捕获 Throwable 而不仅是 Exception：这里要兜住 OutOfMemoryError 等 Error，
            // 否则会穿透到 NanoHTTPD 的请求线程，直接把宿主进程带走
            Log.printStackTrace("Server Error on " + uri, e);
            String errorMsg = "Internal Server Error: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, ServerCommon.MIME_PLAINTEXT, errorMsg);
        }
        logAccess(uri, response, System.currentTimeMillis() - startAt);
        return response;
    }

    /**
     * 访问日志分级：401/403（有人在敲门、功能未开启）与 5xx（故障）记入运行日志，
     * 2xx 只进调试日志——PC 轮询时 200 会持续产生，写进运行日志会把真正有用的行挤掉。
     */
    private void logAccess(String uri, Response response, long costMs) {
        try {
            int status = response.getStatus() != null ? response.getStatus().getRequestStatus() : 0;
            String line = "http " + status + " " + uri + " " + costMs + "ms";
            if (status >= 200 && status < 300) {
                Log.i(line);
            } else {
                Log.record(line);
            }
        } catch (Throwable t) {
            // 记日志本身不能影响响应返回
            Log.printStackTrace("logAccess", t);
        }
    }
    
    /**
     * 🔥【关键修复】安全读取POST/PUT请求体
     * 1. 确保读满content-length指定的字节数
     * 2. 强制UTF-8编码，避免中文乱码
     * @param session HTTP会话
     * @return 读取的请求体字符串，失败返回null
     */
    private String getPostBodySafe(IHTTPSession session) {
        // 解析Content-Length头
        String contentLengthStr = session.getHeaders().get("content-length");
        if (contentLengthStr == null) {
            return null;
        }
        
        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthStr);
        } catch (NumberFormatException e) {
            Log.printStackTrace("Invalid content-length value: " + contentLengthStr, e);
            return null;
        }
        
        // 空Body直接返回空字符串
        if (contentLength == 0) {
            return "";
        }
        // 未鉴权的请求也会走到这里：按声明的长度直接分配内存，Content-Length 写多大就申请多大，
        // 单条请求即可打爆堆。超过上限一律不读，交由上层按空 body 处理
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            Log.i(tag, "拒绝超限请求体: " + contentLength);
            return null;
        }
        
        try {
            byte[] buffer = new byte[contentLength];
            DataInputStream inputStream = new DataInputStream(session.getInputStream());
            // readFully确保读满所有字节（不够则阻塞/抛异常）
            inputStream.readFully(buffer);
            // 明确指定UTF-8编码
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.printStackTrace("Failed to read body", e);
            return null;
        } catch (Throwable e) {
            // 含 OutOfMemoryError：读失败只让本条请求失败，不能穿透到请求线程
            Log.printStackTrace("Error reading body fully", e);
            return null;
        }
    }
    
    /**
     * 返回404响应
     * @return NanoHTTPD Response对象
     */
    private Response notFound() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, ServerCommon.MIME_PLAINTEXT, "Not Found");
    }
}