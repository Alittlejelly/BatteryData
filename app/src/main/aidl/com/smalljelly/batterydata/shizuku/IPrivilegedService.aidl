package com.smalljelly.batterydata.shizuku;

interface IPrivilegedService {
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);

    /**
     * 一键保活白名单：把包名加入 Doze 白名单 + 放开后台运行 appops。
     * 命令集固定在实现里（不接受任意命令），返回逐条结果文本。
     */
    String applyKeepAliveWhitelist(String packageName);
}