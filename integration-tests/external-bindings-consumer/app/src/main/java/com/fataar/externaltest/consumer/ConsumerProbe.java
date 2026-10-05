package com.fataar.externaltest.consumer;
import android.content.Context;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import com.fataar.external.binding.databinding.ExternalBindingBinding;
import com.fataar.externaltest.consumer.databinding.ConsumerExternalBindingBinding;
import com.fataar.externaltest.sdk.ExternalSdkProbe;
public final class ConsumerProbe {
    public static String inspect(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context); FrameLayout parent = new FrameLayout(context);
        ConsumerExternalBindingBinding binding = ConsumerExternalBindingBinding.inflate(inflater, parent, false);
        binding.setConsumerTitle("from-consumer"); binding.executePendingBindings();
        ExternalBindingBinding external = binding.externalIncluded;
        String result = ExternalSdkProbe.inspect(inflater, parent) + "|" + binding.consumerText.getText()
            + ":" + external.externalText.getText() + ":" + binding.embeddedIncluded.externalIncluded.externalText.getText();
        binding.unbind(); return result;
    }
}
