package com.fataar.fixtures.aidl;
public final class FixtureAidlService extends android.app.Service {
    private final IFixtureBridge.Stub binder = new IFixtureBridge.Stub() {
        @Override public FixturePacket exchange(FixturePacket packet) { return new FixturePacket("remote:" + packet.message, packet.sequence + 1); }
        @Override public int remotePid() { return android.os.Process.myPid(); }
    };
    @Override public android.os.IBinder onBind(android.content.Intent intent) { return binder; }
}
