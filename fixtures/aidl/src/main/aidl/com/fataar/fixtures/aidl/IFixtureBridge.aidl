package com.fataar.fixtures.aidl;
import com.fataar.fixtures.aidl.FixturePacket;
interface IFixtureBridge {
    FixturePacket exchange(in FixturePacket packet);
    int remotePid();
}
