package io.github.aw1y2z.sesame.model.extensions;

public class TestRpc {
    private static final String TAG = TestRpc.class.getSimpleName();

    public static void start(String broadcastFun, String broadcastData, String testType) {
        new Thread() {
            String broadcastFun;
            String broadcastData;
            String testType;

            public Thread setData(String fun, String data, String type) {
                broadcastFun = fun;
                broadcastData = data;
                testType = type;
                return this;
            }

            @Override
            public void run() {
                // 裸线程抛出的异常无人接，会走到宿主默认处理器直接杀进程
                try {
                    ExtensionsHandle.handleRequest(testType, broadcastFun, broadcastData);
                } catch (Throwable th) {
                    io.github.aw1y2z.sesame.util.Log.printStackTrace(TAG, th);
                }
            }
        }.setData(broadcastFun, broadcastData, testType).start();
    }
}
