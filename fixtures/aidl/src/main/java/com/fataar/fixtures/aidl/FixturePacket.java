package com.fataar.fixtures.aidl;
import android.os.Parcel;
import android.os.Parcelable;
public final class FixturePacket implements Parcelable {
    public final String message;
    public final int sequence;
    public FixturePacket(String message, int sequence) { this.message = message; this.sequence = sequence; }
    private FixturePacket(Parcel in) { message = in.readString(); sequence = in.readInt(); }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(message); out.writeInt(sequence); }
    @Override public int describeContents() { return 0; }
    public static final Parcelable.Creator<FixturePacket> CREATOR = new Parcelable.Creator<FixturePacket>() {
        @Override public FixturePacket createFromParcel(Parcel in) { return new FixturePacket(in); }
        @Override public FixturePacket[] newArray(int size) { return new FixturePacket[size]; }
    };
}
