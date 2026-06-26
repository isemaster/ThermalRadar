package com.termo1.radar;

import android.content.Context;
import android.os.BatteryManager;

/**
 * BatteryManager — уровень заряда батареи.
 * Читает через Android BatteryManager, возвращает проценты.
 */
public class BatteryLevelReader {
    private final Context context;

    public BatteryLevelReader(Context context) {
        this.context = context;
    }

    public int getBatteryLevel() {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        return -1;
    }

    public int getBatteryColor(int level) {
        if (level < 0) return 0xFF808080;
        return level > 20 ? 0xFF00FF00 : 0xFFFF5050;
    }
}
