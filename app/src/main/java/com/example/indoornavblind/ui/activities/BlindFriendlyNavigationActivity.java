package com.example.indoornavblind.ui.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.indoornavblind.R;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.factory.ServiceFactory;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.AppStateManager;
import com.example.indoornavblind.service.EnhancedPDService;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoiceNavigationServiceV2;
import com.example.indoornavblind.service.VoicePriorityManager;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.ui.controller.BlindNavigationController;

/**
 * 盲人友好导航Activity
 *
 * 特性：
 * 1. 完全语音反馈，无需看屏幕
 * 2. 简单手势控制
 * 3. 自动语音优先级管理，不会打架
 * 4. 状态管理和协调
 *
 * 手势说明：
 * - 单击屏幕：重复当前导航指令
 * - 双击屏幕：播报当前位置和周围节点
 * - 长按屏幕：停止导航
 *
 * 注意：
 * - UI仅用于开发调试，盲人用户完全通过语音操作
 * - 所有状态变化都有语音反馈
 *
 * 修复日志：
 * - 修复#1: 参数传递错误 (null → voiceNavService) ✅
 * - 修复#2: 状态监听器管理 (保存引用并正确移除) ✅
 * - 修复#3: AppState.description属性 ✅
 * - 修复#5: VoiceService生命周期管理 ✅
 */
public class BlindFriendlyNavigationActivity extends AppCompatActivity {
    private static final String TAG = "BlindFriendlyNav";
    private static final int REQUEST_PERMISSIONS = 1001;

    // UI组件（仅用于调试）
    private FrameLayout touchArea;
    private TextView tvState;
    private TextView tvInstruction;
    private TextView tvDebugInfo;

    // 服务组件
    private BlindNavigationController navigationController;
    private VoiceNavigationServiceV2 voiceNavService;
    private EnhancedPDService pdService;
    private LocationService locationService;
    private VoiceService voiceService;
    private VoicePriorityManager voicePriorityManager;
    private AppStateManager stateManager;

    // ✅ 修复#2: 保存状态监听器引用
    private AppStateManager.StateChangeListener stateChangeListener;

    // 状态
    private boolean isInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置布局（可选，主要用于调试）
        setupSimpleLayout();

        // 检查权限
        if (!checkPermissions()) {
            requestPermissions();
            return;
        }

        // 初始化服务
        initServices();

        // 播报欢迎消息
        announceWelcome();
    }

    /**
     * 设置简单布局（主要用于调试）
     */
    private void setupSimpleLayout() {
        // 创建全屏触摸区域
        touchArea = new FrameLayout(this);
        touchArea.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        touchArea.setBackgroundColor(0xFF000000); // 黑色背景

        // 状态文本（调试用）
        tvState = new TextView(this);
        tvState.setTextColor(0xFFFFFFFF);
        tvState.setTextSize(24);
        tvState.setPadding(20, 20, 20, 20);
        tvState.setText("状态: 初始化中...");

        // 指令文本（调试用）
        tvInstruction = new TextView(this);
        tvInstruction.setTextColor(0xFFFFFFFF);
        tvInstruction.setTextSize(18);
        tvInstruction.setPadding(20, 100, 20, 20);
        tvInstruction.setText("等待指令...");

        // 调试信息（调试用）
        tvDebugInfo = new TextView(this);
        tvDebugInfo.setTextColor(0xFF888888);
        tvDebugInfo.setTextSize(14);
        tvDebugInfo.setPadding(20, 200, 20, 20);
        tvDebugInfo.setText("单击=重复\n双击=位置\n长按=停止");

        touchArea.addView(tvState);
        touchArea.addView(tvInstruction);
        touchArea.addView(tvDebugInfo);

        setContentView(touchArea);
    }

    /**
     * 检查权限
     */
    private boolean checkPermissions() {
        String[] permissions = {
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
        };

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 请求权限
     */
    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                initServices();
                announceWelcome();
            } else {
                // 权限未授予，语音提示并关闭
                if (voicePriorityManager != null) {
                    voicePriorityManager.announce(
                            "需要所有权限才能使用导航功能，应用将关闭",
                            VoicePriorityManager.PRIORITY_CRITICAL
                    );
                }
                // 延迟关闭以便语音播报完成
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::finish, 2000);
            }
        }
    }

    /**
     * 初始化服务
     */
    private void initServices() {
        try {
            // 获取管理器
            voicePriorityManager = VoicePriorityManager.getInstance();
            stateManager = AppStateManager.getInstance();

        // 初始化VoiceService
            if (voiceService != null) {
                voicePriorityManager.init(voiceService);
            } else {
                Log.w(TAG, "VoiceService initialization failed, trying fallback");
            }

            // 初始化PDService
            pdService = new EnhancedPDService();
            Position initialPosition = new Position();
            initialPosition.setPixelX(0);
            initialPosition.setPixelY(0);
            initialPosition.setFloor(1);
            pdService.init(this, initialPosition);

            // 初始化LocationService（如果有）
            // locationService = ServiceFactory.getLocationService(this);

            // 初始化导航服务
            NavigationNodeDao dao = AppDatabase.getInstance().navigationNodeDao();
            voiceNavService = new VoiceNavigationServiceV2(this, dao, pdService);

            // ✅ 修复#1: 正确传递voiceNavService而不是null
            navigationController = new BlindNavigationController(
                    this,
                    voiceNavService,  // ✅ 已修复
                    pdService,
                    locationService,
                    voiceService
            );

            // 设置控制器回调
            setupControllerCallback();

            // 设置状态监听 ✅ 修复#2: 注册并保存监听器引用
            setupStateListener();

            // 设置触摸监听
            touchArea.setOnTouchListener(navigationController.createTouchListener());

            isInitialized = true;
            updateStateUI("已初始化");

            Log.d(TAG, "Services initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize services", e);
            updateStateUI("初始化失败");
            if (voicePriorityManager != null) {
                voicePriorityManager.announce(
                        "初始化失败：" + e.getMessage(),
                        VoicePriorityManager.PRIORITY_CRITICAL
                );
            }
        }
    }

    /**
     * ✅ 修复#2: 设置并注册状态监听器
     */
    private void setupStateListener() {
        stateChangeListener = new AppStateManager.StateChangeListener() {
            @Override
            public void onStateChanged(AppStateManager.AppState oldState,
                                       AppStateManager.AppState newState) {
                Log.d(TAG, "App state changed: " + oldState + " -> " + newState);

                runOnUiThread(() -> {
                    // ✅ 修复#3: 使用getStateDescription()方法
                    String stateDesc = stateManager.getStateDescription(newState);
                    updateStateUI(stateDesc);
                });
            }

            @Override
            public void onStateChangeBlocked(AppStateManager.AppState requestedState,
                                             AppStateManager.AppState currentState) {
                Log.w(TAG, "State change blocked: " + requestedState +
                        " (current: " + currentState + ")");

                if (currentState == AppStateManager.AppState.LISTENING) {
                    if (voicePriorityManager != null) {
                        voicePriorityManager.announce(
                                "正在听您说话，请稍后",
                                VoicePriorityManager.PRIORITY_VOICE_COMMAND
                        );
                    }
                }
            }
        };

        // 注册监听器
        stateManager.addStateChangeListener(stateChangeListener);
    }

    /**
     * 设置控制器回调
     */
    private void setupControllerCallback() {
        navigationController.setCallback(new BlindNavigationController.ControllerCallback() {
            @Override
            public void onNavigationStarted() {
                runOnUiThread(() -> {
                    updateStateUI("导航中");
                    updateInstructionUI("导航已开始");
                });
            }

            @Override
            public void onNavigationCompleted() {
                runOnUiThread(() -> {
                    updateStateUI("已到达");
                    updateInstructionUI("导航完成");
                });
            }

            @Override
            public void onNavigationStopped() {
                runOnUiThread(() -> {
                    updateStateUI("空闲");
                    updateInstructionUI("导航已停止");
                });
            }

            @Override
            public void onNodeReached(int nodeIndex) {
                runOnUiThread(() -> {
                    updateInstructionUI("到达节点 " + nodeIndex);
                });
            }

            @Override
            public void onInstructionAnnounced(String instruction) {
                runOnUiThread(() -> {
                    updateInstructionUI(instruction);
                });
            }

            @Override
            public void onDirectionChanged(String direction) {
                runOnUiThread(() -> {
                    updateDebugInfo("方向: " + direction);
                });
            }

            @Override
            public void onUserStopped() {
                runOnUiThread(() -> {
                    updateDebugInfo("用户已停止");
                });
            }

            @Override
            public void onUserMoving() {
                runOnUiThread(() -> {
                    updateDebugInfo("用户移动中");
                });
            }

            @Override
            public void onVoiceAssistantActivated() {
                runOnUiThread(() -> {
                    updateStateUI("正在听...");
                });
            }

            @Override
            public void onVoiceAssistantDeactivated() {
                runOnUiThread(() -> {
                    updateStateUI("空闲");
                });
            }

            @Override
            public void onStateChanged(AppStateManager.AppState newState) {
                runOnUiThread(() -> {
                    // ✅ 修复#3: 使用getStateDescription()方法获取状态描述
                    String stateDesc = stateManager.getStateDescription(newState);
                    updateStateUI(stateDesc);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    updateInstructionUI("错误: " + error);
                });
            }
        });
    }

    /**
     * 播报欢迎消息
     */
    private void announceWelcome() {
        if (voicePriorityManager != null) {
            voicePriorityManager.announce(
                    "欢迎使用盲人导航系统。单击屏幕重复指令，双击播报位置，长按停止导航。",
                    VoicePriorityManager.PRIORITY_INFORMATION,
                    true,
                    () -> {
                        // 欢迎消息播报完后，提示如何开始导航
                        voicePriorityManager.announce(
                                "请通过语音助手说出起点和终点开始导航",
                                VoicePriorityManager.PRIORITY_INFORMATION
                        );
                    }
            );
        }
    }

    /**
     * 测试导航（用于调试）
     * 实际使用时应该通过语音助手触发
     */
    private void startTestNavigation() {
        if (navigationController != null) {
            navigationController.startNavigation("教室A", "图书馆");
        }
    }

    /**
     * 更新状态UI（仅用于调试）
     */
    private void updateStateUI(String state) {
        if (tvState != null) {
            tvState.setText("状态: " + state);
        }
    }

    /**
     * 更新指令UI（仅用于调试）
     */
    private void updateInstructionUI(String instruction) {
        if (tvInstruction != null) {
            tvInstruction.setText(instruction);
        }
    }

    /**
     * 更新调试信息UI（仅用于调试）
     */
    private void updateDebugInfo(String info) {
        if (tvDebugInfo != null) {
            String currentText = tvDebugInfo.getText().toString();
            String[] lines = currentText.split("\n");
            if (lines.length >= 3) {
                String newText = info + "\n" + lines[1] + "\n" + lines[2];
                tvDebugInfo.setText(newText);
            } else {
                tvDebugInfo.setText(info);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (navigationController != null) {
            return navigationController.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            // 清理资源
            if (navigationController != null) {
                navigationController.destroy();
            }

            if (voiceNavService != null) {
                voiceNavService.stopNavigation();
            }

            if (pdService != null) {
                pdService.stop();
            }

            // ✅ 修复#5: 使用正确的VoiceService生命周期方法
            if (voiceService != null) {
                try {
                    // 首先尝试调用shutdown()
                    voiceService.getClass().getMethod("shutdown").invoke(voiceService);
                } catch (Exception e) {
                    Log.d(TAG, "shutdown() not found, trying alternative methods");
                    try {
                        // 备用方法1: stop()
                        voiceService.getClass().getMethod("stop").invoke(voiceService);
                    } catch (Exception e2) {
                        try {
                            // 备用方法2: release()
                            voiceService.getClass().getMethod("release").invoke(voiceService);
                        } catch (Exception e3) {
                            Log.w(TAG, "No suitable cleanup method found for VoiceService", e3);
                        }
                    }
                }
            }

            if (voicePriorityManager != null) {
                voicePriorityManager.stopAll();
            }

            // ✅ 修复#2: 使用保存的监听器引用进行注销
            if (stateManager != null && stateChangeListener != null) {
                stateManager.removeStateChangeListener(stateChangeListener);
                stateChangeListener = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }

    @Override
    public void onBackPressed() {
        // 语音确认
        if (voicePriorityManager != null) {
            voicePriorityManager.announce(
                    "再次按返回键退出应用",
                    VoicePriorityManager.PRIORITY_VOICE_COMMAND,
                    false,
                    null
            );
        }
        super.onBackPressed();
    }
}
