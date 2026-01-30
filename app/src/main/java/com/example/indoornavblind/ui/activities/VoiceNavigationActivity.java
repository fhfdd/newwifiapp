package com.example.indoornavblind.ui.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.indoornavblind.R;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.EnhancedPDService;
import com.example.indoornavblind.service.VoiceNavigationService;
import com.example.indoornavblind.service.VoiceService;

/**
 * 语音导航Activity示例
 * 
 * 使用说明：
 * 1. 点击"开始语音输入"按钮，说出起点和终点
 * 2. 系统自动开始导航并播报指令
 * 3. 根据步数和移动情况自动调整播报时机
 * 4. 到达目的地后自动结束
 */
public class VoiceNavigationActivity extends AppCompatActivity {
    private static final String TAG = "VoiceNavigationActivity";
    private static final int REQUEST_PERMISSIONS = 1001;
    
    // UI组件
    private TextView tvStatus;
    private TextView tvSteps;
    private TextView tvDirection;
    private TextView tvInstruction;
    private Button btnStartVoice;
    private Button btnStopNav;
    private Button btnRepeat;
    
    // 服务组件
    private EnhancedPDService pdService;
    private VoiceNavigationService voiceNavService;
    private VoiceService voiceService;
    private NavigationNodeDao navigationNodeDao;
    
    // 状态
    private boolean isInitialized = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_voice_navigation); // 需要创建布局文件
        
        // 检查并请求权限
        checkPermissions();
        
        // 初始化UI（示例代码，实际需要在布局文件中定义）
        initUI();
        
        // 初始化服务
        initServices();
    }
    
    /**
     * 检查并请求必要的权限
     */
    private void checkPermissions() {
        String[] permissions = {
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        
        boolean needRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        
        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                Toast.makeText(this, "需要所有权限才能使用导航功能", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    /**
     * 初始化UI组件
     */
    private void initUI() {
        // 这里是示例代码，实际需要从布局文件中获取
        // tvStatus = findViewById(R.id.tv_status);
        // tvSteps = findViewById(R.id.tv_steps);
        // tvDirection = findViewById(R.id.tv_direction);
        // tvInstruction = findViewById(R.id.tv_instruction);
        // btnStartVoice = findViewById(R.id.btn_start_voice);
        // btnStopNav = findViewById(R.id.btn_stop_nav);
        // btnRepeat = findViewById(R.id.btn_repeat);
        
        // 按钮点击事件
        // btnStartVoice.setOnClickListener(v -> startVoiceInput());
        // btnStopNav.setOnClickListener(v -> stopNavigation());
        // btnRepeat.setOnClickListener(v -> repeatInstruction());
    }
    
    /**
     * 初始化服务组件
     */
    private void initServices() {
        try {
            // 初始化数据库DAO
            AppDatabase database = AppDatabase.getInstance();
            navigationNodeDao = database.navigationNodeDao();
            
            // 初始化PDService
            pdService = new EnhancedPDService();
            Position initialPosition = new Position();
            initialPosition.setPixelX(0);
            initialPosition.setPixelY(0);
            initialPosition.setFloor(1);
            pdService.init(this, initialPosition);
            
            // 初始化VoiceService（需要从ServiceFactory获取）
            // voiceService = ServiceFactory.getVoiceService(this);
            
            // 初始化VoiceNavigationService
            // voiceNavService = new VoiceNavigationService(
            //     this, navigationNodeDao, pdService, voiceService
            // );
            
            // 设置导航回调
            // setupNavigationCallback();
            
            isInitialized = true;
            Log.d(TAG, "Services initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize services", e);
            Toast.makeText(this, "初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 设置导航回调
     */
    private void setupNavigationCallback() {
        voiceNavService.setNavigationCallback(new VoiceNavigationService.NavigationCallback() {
            @Override
            public void onNavigationStarted(String start, String end, int totalNodes) {
                runOnUiThread(() -> {
                    tvStatus.setText("导航中: " + start + " → " + end);
                    tvInstruction.setText("共" + totalNodes + "个节点");
                });
            }
            
            @Override
            public void onNodeReached(int nodeIndex, String instruction) {
                runOnUiThread(() -> {
                    tvInstruction.setText("节点" + nodeIndex + ": " + instruction);
                });
            }
            
            @Override
            public void onInstructionAnnounced(int nodeIndex, String instruction) {
                runOnUiThread(() -> {
                    tvInstruction.setText(instruction);
                });
            }
            
            @Override
            public void onDirectionUpdated(float direction, String directionDesc) {
                runOnUiThread(() -> {
                    tvDirection.setText(String.format("方向: %.1f° (%s)", direction, directionDesc));
                });
            }
            
            @Override
            public void onUserStopped() {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 已停止");
                });
            }
            
            @Override
            public void onUserMoving() {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 行走中");
                    updateStepsDisplay();
                });
            }
            
            @Override
            public void onNavigationCompleted() {
                runOnUiThread(() -> {
                    tvStatus.setText("导航完成");
                    Toast.makeText(VoiceNavigationActivity.this, 
                        "已到达目的地", Toast.LENGTH_LONG).show();
                });
            }
            
            @Override
            public void onNavigationStopped() {
                runOnUiThread(() -> {
                    tvStatus.setText("导航已停止");
                });
            }
            
            @Override
            public void onNavigationError(String error) {
                runOnUiThread(() -> {
                    tvStatus.setText("错误: " + error);
                    Toast.makeText(VoiceNavigationActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 开始语音输入
     */
    private void startVoiceInput() {
        if (!isInitialized) {
            Toast.makeText(this, "服务未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 这里需要集成语音识别服务
        // 示例：用户说"从教室A到图书馆"
        // 解析后得到起点和终点
        String startLocation = "教室A"; // 从语音识别获取
        String endLocation = "图书馆"; // 从语音识别获取
        
        Toast.makeText(this, "开始导航: " + startLocation + " → " + endLocation, 
            Toast.LENGTH_SHORT).show();
        
        voiceNavService.startVoiceNavigation(startLocation, endLocation);
        
        // 开始定期更新步数显示
        startStepsUpdateLoop();
    }
    
    /**
     * 停止导航
     */
    private void stopNavigation() {
        if (voiceNavService != null) {
            voiceNavService.stopNavigation();
        }
    }
    
    /**
     * 重复当前指令
     */
    private void repeatInstruction() {
        if (voiceNavService != null) {
            voiceNavService.repeatCurrentInstruction();
        }
    }
    
    /**
     * 定期更新步数显示
     */
    private void startStepsUpdateLoop() {
        Runnable updateTask = new Runnable() {
            @Override
            public void run() {
                if (voiceNavService != null && voiceNavService.isNavigating()) {
                    updateStepsDisplay();
                    // 每秒更新一次
                    tvSteps.postDelayed(this, 1000);
                }
            }
        };
        tvSteps.post(updateTask);
    }
    
    /**
     * 更新步数显示
     */
    private void updateStepsDisplay() {
        if (pdService != null) {
            int steps = pdService.getSessionSteps();
            tvSteps.setText("步数: " + steps);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 停止服务
        if (voiceNavService != null) {
            voiceNavService.stopNavigation();
        }
        
        if (pdService != null) {
            pdService.stop();
        }
        
        if (voiceService != null) {
            voiceService.shutdown();
        }
    }
}
