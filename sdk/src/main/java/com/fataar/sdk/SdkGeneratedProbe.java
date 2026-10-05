package com.fataar.sdk;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.TextView;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ObservableField;
import com.fataar.fixtures.viewbinding.databinding.FixtureViewBindingBinding;
import com.fataar.fixtures.databindingleft.databinding.FixtureDataBindingLeftBinding;
import com.fataar.fixtures.databindingright.databinding.FixtureDataBindingRightBinding;
import com.fataar.fixtures.aidl.FixtureAidlService;
import com.fataar.fixtures.aidl.FixturePacket;
import com.fataar.fixtures.aidl.IFixtureBridge;
import com.fataar.sdk.databinding.FixtureSdkBindingBinding;
import java.util.Map;
import java.util.LinkedHashMap;
public final class SdkGeneratedProbe {
    public static Intent serviceIntent(Context context) { return new Intent(context, FixtureAidlService.class); }
    public static Map<String,String> exchange(android.os.IBinder binder) throws android.os.RemoteException {
        IFixtureBridge remote = IFixtureBridge.Stub.asInterface(binder);
        FixturePacket response = remote.exchange(new FixturePacket("request", 41));
        Map<String,String> values = new LinkedHashMap<>();
        values.put("message", response.message); values.put("sequence", Integer.toString(response.sequence));
        values.put("remote-pid", Integer.toString(remote.remotePid()));
        return values;
    }
    public static Map<String,String> inspectBindings(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        Map<String,String> values = new LinkedHashMap<>();
        FixtureViewBindingBinding view = FixtureViewBindingBinding.inflate(inflater);
        values.put("view", view.fixtureBoundTitle.getText().toString());
        view.fixtureBoundTitle.setText("view-updated");
        values.put("view-updated", FixtureViewBindingBinding.bind(view.getRoot()).fixtureBoundTitle.getText().toString());
        FixtureDataBindingLeftBinding left = DataBindingUtil.inflate(inflater, com.fataar.fixtures.databindingleft.R.layout.fixture_data_binding_left, null, false);
        ObservableField<String> model = new ObservableField<>("left-initial");
        left.setLeftModel(model); left.executePendingBindings();
        values.put("left", left.fixtureDataLeftText.getText().toString());
        model.set("left-observed"); left.executePendingBindings();
        values.put("left-updated", left.fixtureDataLeftText.getText().toString());
        FixtureDataBindingRightBinding right = FixtureDataBindingRightBinding.inflate(inflater);
        right.setRightTitle("right"); right.setRightCount(42); right.executePendingBindings();
        values.put("right", right.fixtureDataRightText.getText().toString());
        FixtureSdkBindingBinding host = FixtureSdkBindingBinding.inflate(inflater);
        host.setSdkText("sdk-binding"); host.executePendingBindings();
        values.put("host", ((TextView)host.getRoot()).getText().toString());
        values.put("left-br", DataBindingUtil.convertBrIdToString(com.fataar.fixtures.databindingleft.BR.leftModel));
        values.put("right-br", DataBindingUtil.convertBrIdToString(com.fataar.fixtures.databindingright.BR.rightCount));
        left.unbind(); right.unbind(); host.unbind();
        return values;
    }
}
