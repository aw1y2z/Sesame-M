package io.github.aw1y2z.sesame.hook;

import io.github.aw1y2z.sesame.util.Log;
import fi.iki.elonen.NanoHTTPD;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 宿主内的 HTTP 服务，只监听回环。 */
public class ModuleHttpServer extends NanoHTTPD {
    /** 请求体上限：最大请求是 RPC 参数，1 MB 足够；超限直接拒读，避免按声明长度分配内存 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final String tag = "ModuleHttpServer";
    private final Map<String, HttpHandler> routes = new HashMap<>();
    
    /** 端口 8080、无令牌 */
    public ModuleHttpServer() {
        this(8080, "");
    }
    
    /** 只指定端口，无令牌 */
    public ModuleHttpServer(int port) {
        this(port, "");
    }
    
    /**
     * @param port 监听端口
     * @param secretToken 访问令牌
     */
    public ModuleHttpServer(int port, String secretToken) {
        // 只绑回环：绑 0.0.0.0 时未鉴权路由可被同网段任意设备调用；
        // 从电脑访问用 adb forward tcp:8080 tcp:8080
        super("127.0.0.1", port);
        register("/debugHandler", new DebugHandler(secretToken), "调试接口");
        // 不继承 BaseHandler，令牌传入后由其自行校验
        register("/getAlipayMiniMark", new AlipayMiniMarkHandler(secretToken), "获取支付宝小程序标记");
        // AuthCodeHelper 自建实例、Oauth2AuthCodeFacade 恒为 null，当前宿主版本下必然 500；
        // 保留路由待改为「用宿主已注入的真实实例」后恢复
        register("/getAuthCode", new AuthCodeHandler(secretToken), "获取OAuth2授权码（当前宿主版本下不可用）");
        // 广播控制通道的 HTTP 镜像，语义统一在 ApplicationHook.dispatchControl
        register("/status", new ControlHandler(secretToken, "status"), "宿主在线探测");
        register("/reLogin", new ControlHandler(secretToken, "reLogin"), "重新拉起登录");
        register("/openProfile", new ControlHandler(secretToken, "openProfile"), "打开好友主页");
        register("/restart", new ControlHandler(secretToken, "restart"), "重载 hook");
        register("/reloadConfig", new ControlHandler(secretToken, "reloadConfig"), "重读共享配置");
        register("/execute", new ControlHandler(secretToken, "execute"), "执行任务分组");
        register("/forestQuery", new ControlHandler(secretToken, "forestQuery"), "森林查询（method 白名单）");
        register("/walkPath", new ControlHandler(secretToken, "walkPath"), "自定义走路路径（method 白名单）");
    }
    
    /** 注册路由，无描述 */
    private void register(String path, HttpHandler handler) {
        register(path, handler, "");
    }
    
    /** 注册路由并记录描述 */
    private void register(String path, HttpHandler handler, String description) {
        Log.i("Registering handler : " + path + " -> " + description);
        routes.put(path, handler);
    }
    
    /** 匹配路由 → 读请求体（仅 POST/PUT）→ 调处理器，异常就地兜住 */
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        HttpHandler handler = routes.get(uri);
        if (handler == null) {
            // 404 只进调试日志：回环端口扫描会产生大量 404，写进运行日志会刷爆文件
            Log.i("http 404 " + uri);
            return notFound();
        }

        long startAt = System.currentTimeMillis();
        Response response;
        try {
            String body = null;
            Method requestMethod = session.getMethod();
            if (requestMethod == Method.POST || requestMethod == Method.PUT) {
                body = getPostBodySafe(session);
            }
            response = handler.handle(session, body);
        } catch (Throwable e) {
            // 捕获 Throwable：OOM 等 Error 穿透会带走宿主进程
            Log.printStackTrace("Server Error on " + uri, e);
            String errorMsg = "Internal Server Error: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, ServerCommon.MIME_PLAINTEXT, errorMsg);
        }
        logAccess(uri, response, System.currentTimeMillis() - startAt);
        return response;
    }

    /** 访问日志分级：2xx 只进调试日志（PC 轮询会刷屏），401/403/5xx 进运行日志 */
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
            // 记日志不能影响响应返回
            Log.printStackTrace("logAccess", t);
        }
    }
    
    /** 按 content-length 读满请求体并强制 UTF-8；超限或读取失败返回 null */
    private String getPostBodySafe(IHTTPSession session) {
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
        
        if (contentLength == 0) {
            return "";
        }
        // 未鉴权的请求也会到这里：超限不读，避免按声明长度分配内存打爆堆
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            Log.i(tag, "拒绝超限请求体: " + contentLength);
            return null;
        }
        
        try {
            byte[] buffer = new byte[contentLength];
            DataInputStream inputStream = new DataInputStream(session.getInputStream());
            inputStream.readFully(buffer);
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
    
    /** 返回 404 */
    private Response notFound() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, ServerCommon.MIME_PLAINTEXT, "Not Found");
    }
}
