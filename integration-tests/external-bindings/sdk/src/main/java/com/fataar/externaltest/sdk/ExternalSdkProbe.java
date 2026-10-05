package com.fataar.externaltest.sdk;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import com.fataar.externaltest.sdk.databinding.SdkExternalBindingBinding;
public final class ExternalSdkProbe {
    public static String inspect(LayoutInflater inflater, ViewGroup parent) {
        SdkExternalBindingBinding binding = SdkExternalBindingBinding.inflate(inflater, parent, false);
        binding.setSdkTitle("from-sdk"); binding.executePendingBindings();
        String result = binding.sdkText.getText() + ":" + binding.embeddedIncluded.embeddedText.getText()
            + ":" + binding.embeddedIncluded.externalIncluded.externalText.getText();
        binding.unbind(); return result;
    }
}
