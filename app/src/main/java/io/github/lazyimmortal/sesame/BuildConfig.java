// ⚠️ 注意：这个类只是转发到真正的 BuildConfig，避免重复维护字段
package io.github.lazyimmortal.sesame;

// 导入真正的自动生成的 BuildConfig
public final class BuildConfig {

    // ===== 转发自动生成的字段 =====
    public static final boolean DEBUG = io.github.aw1y2z.sesame.BuildConfig.DEBUG;
    public static final String APPLICATION_ID = "io.github.aw1y2z.sesame";
    public static final String BUILD_TYPE = io.github.aw1y2z.sesame.BuildConfig.BUILD_TYPE;
    public static final int VERSION_CODE = io.github.aw1y2z.sesame.BuildConfig.VERSION_CODE;
    public static final String VERSION_NAME = io.github.aw1y2z.sesame.BuildConfig.VERSION_NAME;

    // ===== 转发自定义的字段 =====
    public static final String GIT_COMMIT_HASH = io.github.aw1y2z.sesame.BuildConfig.GIT_COMMIT_HASH;
    public static final String GIT_BRANCH_NAME = io.github.aw1y2z.sesame.BuildConfig.GIT_BRANCH_NAME;
    public static final String BUILD_TIME = io.github.aw1y2z.sesame.BuildConfig.BUILD_TIME;

    // ===== 防止实例化 =====
    private BuildConfig() {}
}
