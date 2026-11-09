package io.github.aw1y2z.sesame.hook;

import fi.iki.elonen.NanoHTTPD;

import java.util.HashMap;
import java.util.Map;

/**
 * 受控通道的 HTTP 入口：与广播 {@code sesame.control} 共用 {@link ApplicationHook#dispatchControl}，
 * 语义与副作用一致（HTTP 不通时「重启」只能靠广播）。
 * <p>不做频控：只监听回环且每个请求都要令牌；限流是给广播那条路防令牌泄露用的。
 * <p>参数走 query：{@code userId}、{@code group}、{@code data}、{@code exec=1}。
 */
public class ControlHandler extends BaseHandler {
    private final String op;

    public ControlHandler(String secretToken, String op) {
        super(secretToken);
        this.op = op;
    }

    @Override
    protected NanoHTTPD.Response onGet(NanoHTTPD.IHTTPSession session) {
        // 会改变宿主行为的 op 不接受 GET：链接预取可能随时命中 URL
        if (!"status".equals(op) && !"openProfile".equals(op)) {
            return methodNotAllowed();
        }
        return run(session);
    }

    @Override
    protected NanoHTTPD.Response onPost(NanoHTTPD.IHTTPSession session, String body) {
        return run(session);
    }

    private NanoHTTPD.Response run(NanoHTTPD.IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String userId = params.get("userId");
        String group = params.get("group");
        String dataParam = params.get("data");
        String execParam = params.get("exec");
        boolean exec = "1".equals(execParam) || "true".equalsIgnoreCase(execParam);

        // reLogin 不在广播白名单里（模块 App 用不到），只给 PC 侧排查登录态用
        if ("reLogin".equals(op)) {
            ApplicationHook.reLogin();
        } else {
            String rejected = ApplicationHook.dispatchControl(op, userId, group, dataParam, exec);
            if (rejected != null) {
                return badRequest(rejected);
            }
        }

        Map<String, String> data = new HashMap<>();
        data.put("status", "ok");
        data.put("op", op);
        return ok(data);
    }
}
