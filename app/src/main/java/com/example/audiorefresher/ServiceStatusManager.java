package com.example.audiorefresher;

import androidx.lifecycle.MutableLiveData;

public class ServiceStatusManager {
    // 使用 LiveData 包装布尔值
    public static final MutableLiveData<Boolean> isRunning = new MutableLiveData<>(false);
    public static final MutableLiveData<Integer> refreshCount = new MutableLiveData<>(0);

    public static MutableLiveData<Boolean> getIsRunning() {
        return isRunning;
    }

    public static MutableLiveData<Integer> getRefreshCount() {
        return refreshCount;
    }

    // 更新状态的方法
    public static void setRunning(boolean running) {
        isRunning.postValue(running);
    }

    public static void updateCount(int count) {
        refreshCount.postValue(count);
    }
}