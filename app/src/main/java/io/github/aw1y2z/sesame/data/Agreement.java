package io.github.aw1y2z.sesame.data;

import io.github.aw1y2z.sesame.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 用户协议（合规与免责条款）的同意状态。
 * <p>条款正文在仓库文档 {@code docs/合规与免责.md}，界面 {@code AgreementScreen} 是其摘要；
 * 本文只负责「哪个版本已同意」，不承载正文。
 * <p>状态存 {@link AppConfig}，因此是设备级、跨账号共享，且卸载重装后才会重置。
 */
public final class Agreement {

    /**
     * 条款版本。递增时须同步三处：{@code docs/合规与免责.md} 的版本记录表、
     * 界面 {@code AgreementScreen} 的 {@code TERMS} 摘要、README 的「合规与免责」条目；
     * 否则用户同意的是新版本，看到的却还是旧条文。
     */
    public static final int VERSION = 1;

    /** 用户需逐字输入的确认短语，用于把「点一下」变成「确实读过」 */
    public static final String ACCEPT_PHRASE = "我已阅读并同意";

    private Agreement() {
    }

    /** 当前设备是否已同意当前版本的条款 */
    public static boolean isAccepted() {
        // 未加载时读到的是默认值，会把已同意的设备误判成未同意
        if (!AppConfig.INSTANCE.isInit()) {
            AppConfig.load();
        }
        Integer acceptedVersion = AppConfig.INSTANCE.getAgreementVersion();
        return acceptedVersion != null && acceptedVersion >= VERSION;
    }

    /**
     * 记录同意。
     * <p>必须回写成功才算同意：{@link AppConfig#save()} 在「上次加载失败」时会拒绝写盘，
     * 此时内存里只是默认值，若直接放行，下次启动会再次弹窗。
     *
     * @return 是否已成功落盘
     */
    public static boolean accept() {
        AppConfig config = AppConfig.INSTANCE;
        Integer previousVersion = config.getAgreementVersion();
        Long previousAcceptedAt = config.getAgreementAcceptedAt();

        config.setAgreementVersion(VERSION);
        config.setAgreementAcceptedAt(System.currentTimeMillis());

        Boolean saved = AppConfig.save();
        if (!Boolean.TRUE.equals(saved) && AppConfig.isLoadFailed()) {
            // 配置解析失败时 save() 会一直拒绝写盘，协议页将永远无法完成同意；
            // 用户此刻已明确表达同意，改为备份损坏文件后重写，别把人锁死在门禁页
            saved = AppConfig.forceSave();
        }
        if (!Boolean.TRUE.equals(saved)) {
            // 回滚：内存留着「已同意」而磁盘没有，会让 onResume 与下次启动的判断不一致
            config.setAgreementVersion(previousVersion);
            config.setAgreementAcceptedAt(previousAcceptedAt);
            Log.i("协议同意状态写入失败");
            return false;
        }
        Log.i("已同意用户协议 v" + VERSION);
        return true;
    }

    /** 同意时间（yyyy-MM-dd HH:mm），未记录时返回 null，供「关于」页展示 */
    public static String acceptedAtText() {
        if (!AppConfig.INSTANCE.isInit()) {
            AppConfig.load();
        }
        Long acceptedAt = AppConfig.INSTANCE.getAgreementAcceptedAt();
        if (acceptedAt == null || acceptedAt <= 0L) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(acceptedAt));
    }
}
