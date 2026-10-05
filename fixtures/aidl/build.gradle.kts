plugins { id("com.fataar.playground.android-library") }
android { buildFeatures { aidl = true } }
android { aidlPackagedList.add("com/fataar/fixtures/aidl/FixturePacket.aidl") }
