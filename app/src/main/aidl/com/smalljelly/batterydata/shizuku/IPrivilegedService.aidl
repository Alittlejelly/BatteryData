package com.smalljelly.batterydata.shizuku;

interface IPrivilegedService {
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);
}