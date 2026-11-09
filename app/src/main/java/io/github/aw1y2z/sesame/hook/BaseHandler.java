package io.github.aw1y2z.sesame.hook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import io.github.aw1y2z.sesame.data.AppConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP处理器基类（模板方法模式）
 * 统一处理鉴权、异常和请求方法分发（对应原Kotlin的BaseHandler）
 */
public abstract class BaseHandler implements io.github.aw1y2z.sesame.hook.HttpHandler {
    private final String secretToken;
    protected final ObjectMapper mapper;
    
    /**
     * 构造方法（对应Kotlin的主构造器）
     * @param secretToken 鉴权秘钥令牌
     */
    public BaseHandler(String secretToken) {
        this.secretToken = secretToken;
        // 复用ServerCommon的全局ObjectMapper单例
        this.mapper = ServerCommon.jsonMapper;
    }
    
    /**
     * 模板方法：统一处理鉴权、异常和请求方法分发（final禁止子类重写）
     */
    @Override
    public final NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String body) {
        try {
            // 1. 鉴权验证，失败返回401
            if (!verifyToken(session)) {
                return unauthorized();
            }

            // 1.5 功能门禁：高危路由需用户显式开启调试（由子类声明，基类不判断具体路径）。
            // 复用「抓包记录」而非独立开关：它是调试专用、默认关闭，与 /debugHandler 的使用场景重合
            if (requiresEnableSwitch() && !Boolean.TRUE.equals(AppConfig.INSTANCE.getEnableDebugLog())) {
                return forbidden(enableSwitchTip());
            }

            // 2. 根据请求方法分发处理
            NanoHTTPD.Method method = session.getMethod();
            if (method == NanoHTTPD.Method.GET) {
                return onGet(session);
            } else if (method == NanoHTTPD.Method.POST) {
                return onPost(session, body);
            } else {
                return methodNotAllowed();
            }
        } catch (Exception e) {
            // 3. 全局异常捕获，返回500响应
            e.printStackTrace(); // 打印堆栈到Logcat
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            Map<String, String> errorData = new HashMap<>();
            errorData.put("status", "error");
            errorData.put("message", errorMsg);
            return json(NanoHTTPD.Response.Status.INTERNAL_ERROR, errorData);
        }
    }
    
    /**
     * 该路由是否还需要「用户显式开启」这道门禁（默认不需要）。
     * <p>声明式钩子而不是在基类里按 uri 判断：路由知识不应塞进基类，子类覆写即可，
     * 未覆写的路由行为完全不变。
     */
    protected boolean requiresEnableSwitch() {
        return false;
    }

    /** 门禁未开启时的提示文案，由需要的子类覆写 */
    protected String enableSwitchTip() {
        return "功能未开启";
    }

    private boolean verifyToken(NanoHTTPD.IHTTPSession session) {
        return verifyToken(secretToken, session);
    }

    /**
     * 验证鉴权Token：静态可复用，供不继承本类的路由在入口自行校验。
     * <p>令牌为 null/空白一律拒绝（不做「无令牌即放行」）；支持 Bearer 前缀、忽略大小写，
     * 用常数时间比较避免逐位比较泄漏令牌。
     *
     * @param secretToken 期望的令牌
     * @param session     HTTP 会话
     * @return 鉴权是否通过
     */
    protected static boolean verifyToken(String secretToken, NanoHTTPD.IHTTPSession session) {
        // 未配置令牌时一律拒绝：不做「无令牌即放行」的默认开放
        if (secretToken == null || secretToken.trim().isEmpty() || session == null) {
            return false;
        }

        // 获取Authorization请求头
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader == null) {
            return false;
        }

        // 解析Token（支持Bearer前缀，忽略大小写）
        String token;
        String bearerPrefix = "Bearer ";
        if (authHeader.toLowerCase().startsWith(bearerPrefix.toLowerCase())) {
            token = authHeader.substring(bearerPrefix.length()).trim();
        } else {
            token = authHeader.trim();
        }

        // 常数时间比较，避免按字符逐位比较泄漏令牌
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                secretToken.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 处理GET请求（子类可重写，默认返回405）
     * @param session HTTP会话
     * @return HTTP响应
     */
    protected NanoHTTPD.Response onGet(NanoHTTPD.IHTTPSession session) {
        return methodNotAllowed();
    }
    
    /**
     * 处理POST请求（子类可重写，默认返回405）
     * @param session HTTP会话
     * @param body POST请求体
     * @return HTTP响应
     */
    protected NanoHTTPD.Response onPost(NanoHTTPD.IHTTPSession session, String body) {
        return methodNotAllowed();
    }
    
    // --- 响应辅助方法（protected，子类可调用） ---
    
    /**
     * 构建JSON格式响应
     * @param status HTTP状态码
     * @param data 响应数据（String直接使用，其他对象序列化为JSON）
     * @return HTTP响应
     */
    protected NanoHTTPD.Response json(NanoHTTPD.Response.Status status, Object data) {
        String jsonText;
        if (data instanceof String) {
            // 若数据是字符串，直接使用（避免重复序列化）
            jsonText = (String) data;
        } else {
            // 其他类型序列化为JSON字符串
            try {
                jsonText = mapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                // 序列化失败返回默认错误JSON
                jsonText = "{\"status\":\"error\",\"message\":\"Failed to serialize response\"}";
            }
        }
        return NanoHTTPD.newFixedLengthResponse(status, ServerCommon.MIME_JSON, jsonText);
    }
    
    /**
     * 构建200 OK的JSON响应
     * @param data 响应数据
     * @return HTTP响应
     */
    protected NanoHTTPD.Response ok(Object data) {
        return json(NanoHTTPD.Response.Status.OK, data);
    }
    
    /**
     * 构建400 Bad Request的JSON响应
     * @param message 错误信息
     * @return HTTP响应
     */
    protected NanoHTTPD.Response badRequest(String message) {
        Map<String, String> data = new HashMap<>();
        data.put("status", "error");
        data.put("message", message);
        return json(NanoHTTPD.Response.Status.BAD_REQUEST, data);
    }
    
    /**
     * 构建401 Unauthorized的JSON响应
     * @return HTTP响应
     */
    protected NanoHTTPD.Response unauthorized() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "unauthorized");
        return json(NanoHTTPD.Response.Status.UNAUTHORIZED, data);
    }

    /**
     * 构建403 Forbidden的JSON响应（令牌正确但功能未开启）
     * @param message 提示信息，需说明去哪儿开启
     * @return HTTP响应
     */
    protected NanoHTTPD.Response forbidden(String message) {
        Map<String, String> data = new HashMap<>();
        data.put("status", "forbidden");
        data.put("message", message);
        return json(NanoHTTPD.Response.Status.FORBIDDEN, data);
    }
    
    /**
     * 构建405 Method Not Allowed的JSON响应
     * @return HTTP响应
     */
    protected NanoHTTPD.Response methodNotAllowed() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "method_not_allowed");
        return json(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, data);
    }
    
    /**
     * 构建404 Not Found的JSON响应
     * @return HTTP响应
     */
    protected NanoHTTPD.Response notFound() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "not_found");
        return json(NanoHTTPD.Response.Status.NOT_FOUND, data);
    }
}