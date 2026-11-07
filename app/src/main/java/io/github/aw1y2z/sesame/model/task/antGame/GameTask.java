package io.github.aw1y2z.sesame.model.task.antGame;

import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.AuthCodeHelper;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * 游戏任务上报工具类
 * 对应原Kotlin的GameTask枚举类
 */
public enum GameTask {

    Orchard_ncscc("农场上车车", "2060170000356601", "zfb_ncscc", "ncscc_game_kaiche_every_10", "nongchangleyuan", "1.0.2", 2),
    Farm_ddply("对对碰乐园", "2021004149679303", "zfb_ddply", "ddply_game_xiaochu_every_5", "zhuangyuan", "1.0.14", 2),
    Forest_slxcc("森林小车车", "2060170000363691", "zfb_slxcc", "slxcc_game_kaiche_every_10", "lianyun_senlin_leyuan", "1.0.1", 3),
    Forest_sljyd("森林救援队(能量雨)", "2021005113684028", "zfb_sljydx", "sljyd_game_xiaochu_every_10", "lianyun_senlin_leyuan", "1.0.1", 3);

    private final String title;
    private final String appId;
    private final String gid;
    private final String action;
    private final String channel;
    private final String version;
    private final int requestsPerEgg; // 完成1个🥚要多少次 为了防止网络崩溃 多加1次
    private String cachedToken; // 缓存登录Token

    /**
     * 外网游戏服的连接/读取超时。
     * <p>{@code reportSync} 在任务线程同步执行，超时过长会把整轮任务卡死；读超时留得比连接长以容忍弱网首包。
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    /**
     * 根据小程序 appId 匹配游戏任务（金豆乐园游戏权益上报使用）
     */
    public static GameTask matchAppId(String appId) {
        if (appId == null || appId.isEmpty()) {
            return null;
        }
        for (GameTask task : values()) {
            if (appId.equals(task.appId)) {
                return task;
            }
        }
        return null;
    }

    public String getAppId() {
        return appId;
    }

    public String getTitle() {
        return title;
    }

    /**
     * 枚举构造方法
     */
    GameTask(String title, String appId, String gid, String action, String channel, String version, int requestsPerEgg) {
        this.title = title;
        this.appId = appId;
        this.gid = gid;
        this.action = action;
        this.channel = channel;
        this.version = version;
        this.requestsPerEgg = requestsPerEgg;
    }

    /**
     * 第一步：登录获取 Token 并缓存
     */
    private String login() {
        try {
            String authCode = AuthCodeHelper.getAuthCode(appId);
            // 小程序标记（紧邻的 alipayMiniMark 请求头）在当前支付宝版本上必然为空：
            // 承载它的宿主类 H5HttpUtils 已不存在（AlipayMiniMarkHelper 探测两个候选类名都找不到，
            // 并会在日志里说明一次）。这里直接用空串，省掉逐游戏的反射调用；
            // 将来某版支付宝恢复该能力时，把空串换回 AlipayMiniMarkHelper.getAlipayMiniMark(appId, version) 即可。
            String mark = "";
            String reqId = System.currentTimeMillis() + "_" + new Random().nextInt(350) + 1;

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("v", version);
            // 注：authCode 在当前支付宝版本上必然为 null（AuthCodeHelper 自建实例未走宿主依赖注入，
            // 其内部 facade 为 null 会抛 NPE），但实测游戏服并不校验该字段、登录仍能拿到 token；
            // 真正生效的凭据是下面的 header「alipayMiniMark」。若将来出现登录失败，优先怀疑这里。
            bodyJson.put("code", authCode);
            bodyJson.put("pf", "zfb");
            bodyJson.put("reqId", reqId);
            bodyJson.put("gid", gid);
            bodyJson.put("version", version);
            String body = bodyJson.toString();


            // 建立HTTP连接
            URL url = new URL("https://gamesapi2.aslk2018.com/v2/game/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            // 防止外部游戏服连接挂起导致当前线程无限阻塞（主任务线程会因此冻结，只能重启恢复）
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("alipayMiniMark", mark);
            conn.setRequestProperty("User-Agent", getDynamicUA());
            conn.setRequestProperty("x-release-type", "ONLINE");

            // 写入请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body);
            }

            // 处理响应（包含错误流）
            int respCode = conn.getResponseCode();
            StringBuilder responseText = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    respCode >= 200 && respCode <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseText.append(line);
                }
            } finally {
                // HttpURLConnection 没有 close()，异常路径也必须 disconnect 才能释放连接
                conn.disconnect();
            }


            // 解析响应JSON
            JSONObject resJson = new JSONObject(responseText.toString());
            if (resJson.optInt("code") == 1) {
                JSONObject data = resJson.optJSONObject("data");
                if (data != null) {
                    this.cachedToken = data.optString("token");
                    Log.other("登录成功✅Token已获取");
                    return this.cachedToken;
                }
            } else {
                Log.error("登录接口⚠️报错(Code" + respCode + "):" + responseText);
            }
        } catch (Exception e) {
            Log.error("登录过程🚨抛出异常:" + e.getMessage());
        }
        return null;
    }

    /**
     * 外部调用：执行上报任务
     * @param eggCount 目标蛋数量
     */
    public void report(String gameType,int eggCount) {
        int totalNeeded = eggCount * (this.requestsPerEgg + 1); // 多1次确保网络请求不会错误
        final String reportUid = UserIdMap.getCurrentUid();
        new Thread(() -> {
            if (!java.util.Objects.equals(reportUid, UserIdMap.getCurrentUid())) {
                Log.record("任务流程⚠️账号已切换，停止上报");
                return;
            }
            this.cachedToken = login();
            if (this.cachedToken == null || this.cachedToken.isEmpty()) {
                 Log.error("无法获取⚠️有效的Token，放弃上报任务");
                return;
            }

            Log.record("开始执行🚀"+gameType+"游戏任务:目标" + eggCount + "个蛋，需请求" + totalNeeded + "次");
            for (int i = 1; i <= totalNeeded; i++) {
                if (!java.util.Objects.equals(reportUid, UserIdMap.getCurrentUid())) {
                    Log.record("任务流程⚠️账号已切换，停止上报");
                    break;
                }
                if (!executeSingleReport(gameType,i, totalNeeded)) {
                    // 具体的错误原因已在 executeSingleReport 中详细输出
                    break;
                }
                if (i < totalNeeded) {
                    try {
                        Thread.sleep(new Random().nextInt(2001) + 1000); // 1000-3000ms随机休眠
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            Log.record("任务流程🏁运行结束");
        }).start();
    }

    /**
     * 同步执行上报任务，返回成功上报次数。
     * 用于需要等待结果并回查服务端状态的场景（如金豆乐园游戏权益）。
     *
     * @param gameType 日志展示用的场景名
     * @param eggCount 目标蛋数量
     * @return 成功上报的次数，失败返回已成功的次数
     */
    public int reportSync(String gameType, int eggCount) {
        return reportSync(gameType, eggCount, null);
    }

    /**
     * 同步执行上报任务，可覆盖上报渠道。
     * <p>金豆乐园场景必须传 {@code "goldenbean"}，
     * 否则游戏服接受上报但支付宝侧权益不推进。
     *
     * @param channelOverride 非空时覆盖 action_finish_channel；为空用枚举默认渠道
     */
    public int reportSync(String gameType, int eggCount, String channelOverride) {
        return reportSync(gameType, eggCount, channelOverride, null);
    }

    /**
     * 同步执行上报任务，每成功一次就询问 {@code reached} 是否已达目标。
     *
     * <p>服务端达成所需次数由接口内部决定、协议未给出，故不能把固定次数当准绳：
     * 以真实状态推进为准，{@link #requestsPerEgg} 仅作上限兜底。已达目标即停发，
     * 可免掉多余的外网直连（这类请求绕过支付宝网关，是风控最敏感的部分）。
     *
     * @param channelOverride 非空时覆盖 action_finish_channel；为空用枚举默认渠道
     * @param reached         每次上报成功后回调，返回 {@code true} 表示目标已达成、不再续发；为 null 则发满次
     * @return 成功上报的次数
     */
    public int reportSync(String gameType, int eggCount, String channelOverride, BooleanSupplier reached) {
        if (eggCount <= 0) {
            return 0;
        }
        int maxReports = eggCount * this.requestsPerEgg;
        this.cachedToken = login();
        if (this.cachedToken == null || this.cachedToken.isEmpty()) {
            Log.error("无法获取⚠️有效的Token，放弃上报任务");
            return 0;
        }

        int successfulReports = 0;
        for (int i = 1; i <= maxReports; i++) {
            if (!executeSingleReport(gameType, i, maxReports, channelOverride)) {
                break;
            }
            successfulReports++;
            // 已达成就不必再发：真实状态比固定的 requestsPerEgg 可靠
            if (reached != null && reached.getAsBoolean()) {
                break;
            }
            if (i < maxReports) {
                try {
                    Thread.sleep(new Random().nextInt(2001) + 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return successfulReports;
    }

    /**
     * 执行单次上报请求
     * @param current 当前请求次数
     * @param total 总请求次数
     * @return 是否上报成功
     */
    private boolean executeSingleReport(String gameType, int current, int total) {
        return executeSingleReport(gameType, current, total, null);
    }

    private boolean executeSingleReport(String gameType, int current, int total, String channelOverride) {
        try {
            // 同 login()：当前宿主没有 H5HttpUtils，标记必然为空，直接用空串（header 值不变）
            String mark = "";
            String reqId = System.currentTimeMillis() + "_" + (new Random().nextInt(90) + 10); // 10-99随机数

            // 构建请求体
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("v", version);
            bodyJson.put("version", version);
            bodyJson.put("reqId", reqId);
            bodyJson.put("gid", gid);
            bodyJson.put("action_code", action);
            bodyJson.put("action_finish_channel",
                    channelOverride != null && !channelOverride.isEmpty() ? channelOverride : channel);
            String body = bodyJson.toString();

            // 建立HTTP连接
            URL url = new URL("https://gamesapi2.aslk2018.com/v2/zfb/taskReport");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            // 防止外部游戏服连接挂起导致当前线程无限阻塞（reportSync 在主任务线程同步执行）
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("authorization", this.cachedToken);
            conn.setRequestProperty("alipayMiniMark", mark);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", getDynamicUA());
            conn.setRequestProperty("x-release-type", "ONLINE");
            conn.setRequestProperty("referer", "https://" + appId + ".hybrid.alipay-eco.com/" + appId + "/" + version + "/index.html");

            // 写入请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body);
            }

            // 处理响应
            int respCode = conn.getResponseCode();
            StringBuilder responseText = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    respCode >= 200 && respCode <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseText.append(line);
                }
            } finally {
                // HttpURLConnection 没有 close()，异常路径也必须 disconnect 才能释放连接
                conn.disconnect();
            }

            // 解析响应
            JSONObject resJson = new JSONObject(responseText.toString());
            if (resJson.optInt("code") == 1) {
                if (current % this.requestsPerEgg == 0) {
                    Log.i("游戏进度📈"+ gameType +"[" + current + "/" + total + "](达成" + (current/this.requestsPerEgg) + "个)");
                }
                return true;
            } else {
                Log.error("⚠️ 第 " + current + " 次上报业务失败 (HTTP " + respCode + "): " + responseText);
                return false;
            }
        } catch (IOException e) {
            Log.error("🚨 第 " + current + " 次请求发生网络崩溃:"+ e);
            return false;
        } catch (Exception e) {
            Log.error("🚨 第 " + current + " 次请求发生异常:"+ e);
            return false;
        }
    }

    /**
     * 获取动态User-Agent
     * @return 拼接后的UA字符串
     */
    private String getDynamicUA() {
        String systemUa = System.getProperty("http.agent");
        if (systemUa == null || systemUa.isEmpty()) {
            systemUa = "Mozilla/5.0 (Linux; Android 11)";
        }
        String alipayVer = String.valueOf(ApplicationHook.getAlipayVersion());
        return systemUa + " NebulaSDK/1.8.100112 Nebula AliApp(AP/" + alipayVer + ") AlipayClient/" + alipayVer;
    }
}