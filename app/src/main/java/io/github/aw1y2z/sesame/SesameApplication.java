package io.github.aw1y2z.sesame;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.aw1y2z.sesame.data.RunType;
import io.github.aw1y2z.sesame.data.ViewAppInfo;
import io.github.aw1y2z.sesame.ui.HostBridge;

/**
 * 模块自身 App 进程的 Application。
 * <p>
 * 收到 onServiceBind 即代表本模块已被 LSPosed 启用，与支付宝是否运行无关。
 */
public class SesameApplication extends Application implements XposedServiceHelper.OnServiceListener {

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
        // 首次启动先配对：此时宿主多半没在跑，进前台时 MiuixMainActivity 会再试一次
        HostBridge.ensurePaired(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        android.util.Log.i("SesameX", "onServiceBind framework=" + service.getFrameworkName()
                + " scope=" + service.getScope());
        ViewAppInfo.setRunTypeByCode(RunType.MODEL.getCode());
    }

    @Override
    public void onServiceDied(XposedService service) {
        android.util.Log.i("SesameX", "onServiceDied");
        ViewAppInfo.setRunTypeByCode(RunType.DISABLE.getCode());
    }
}
