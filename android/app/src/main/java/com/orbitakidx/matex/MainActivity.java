package com.orbitakidx.matex;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OrbitakidxBillingPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
