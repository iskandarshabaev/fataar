package com.fataar.consumerprobe;
import com.fataar.fixtures.aidl.FixturePacket;
import androidx.databinding.ObservableField;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ConsumerGeneratedProbe {
    public static Map<String,String> inspect(android.content.Context context) throws android.os.RemoteException {
        ConsumerBinding binding = ConsumerBinding.inflate(android.view.LayoutInflater.from(context));
        ObservableField<String> model = new ObservableField<>("consumer-left");
        binding.setConsumerModel(model); binding.setConsumerTitle("consumer-right"); binding.setConsumerCount(7);
        binding.executePendingBindings();
        Map<String,String> values = new LinkedHashMap<>();
        values.put("left", binding.consumerLeft.fixtureDataLeftText.getText().toString());
        values.put("right", binding.consumerRight.fixtureDataRightText.getText().toString());
        values.put("adapter", binding.consumerAdapter.getText().toString());
        model.set("consumer-observed"); binding.executePendingBindings();
        binding.consumerLeft.executePendingBindings();
        values.put("left-updated", binding.consumerLeft.fixtureDataLeftText.getText().toString());
        IConsumerPacketEcho ownGeneratedStub = new IConsumerPacketEcho.Stub() {
            @Override public FixturePacket echo(FixturePacket packet) { return new FixturePacket("import:" + packet.message, packet.sequence + 1); }
        };
        FixturePacket packet = ownGeneratedStub.echo(new FixturePacket("parcel", 8));
        values.put("aidl-import", packet.message + ":" + packet.sequence);
        binding.unbind(); return values;
    }
}
