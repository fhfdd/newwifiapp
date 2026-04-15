package com.example.indoornavblind.ui.view;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 呼吸灯光晕效果的自定义View
 * 参考AI助手唤起效果，实现流畅的波纹扩散、多重圆环和颜色渐变
 */
public class GlowView extends View {

    // 主光晕画笔
    private Paint mainGlowPaint;
    // 波纹画笔
    private Paint ripplePaint;
    // 中心脉冲画笔
    private Paint corePaint;

    private float maxGlowRadius;
    private int glowColor = Color.parseColor("#00FF00");

    // 动画状态
    private float mainGlowRadius = 0f;
    private float mainAlpha = 0f;
    private float coreScale = 0f;

    // 波纹效果
    private List<Ripple> ripples = new ArrayList<>();
    private long lastRippleTime = 0;

    private AnimatorSet glowAnimatorSet;
    private ValueAnimator rippleAnimator;
    private boolean isAnimating = false;

    // 波纹类
    private static class Ripple {
        float radius;
        float alpha;
        float speed;

        Ripple(float radius, float alpha, float speed) {
            this.radius = radius;
            this.alpha = alpha;
            this.speed = speed;
        }
    }

    public GlowView(Context context) {
        super(context);
        init();
    }

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 主光晕画笔 - 使用渐变
        mainGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mainGlowPaint.setStyle(Paint.Style.FILL);

        // 波纹画笔 - 描边
        ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(3f);

        // 中心脉冲画笔
        corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        corePaint.setStyle(Paint.Style.FILL);

        maxGlowRadius = 100f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        maxGlowRadius = Math.max(w, h) * 0.55f;

        // 更新主光晕渐变
        updateMainGlowGradient();
    }

    private void updateMainGlowGradient() {
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // 创建径向渐变
        RadialGradient gradient = new RadialGradient(
                centerX, centerY, maxGlowRadius,
                new int[] {
                        Color.argb(180, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                        Color.argb(100, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                        Color.argb(20, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                        Color.TRANSPARENT
                },
                new float[] {0f, 0.4f, 0.7f, 1f},
                Shader.TileMode.CLAMP
        );
        mainGlowPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mainAlpha <= 0f && ripples.isEmpty()) {
            return;
        }

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // 1. 绘制主光晕（渐变效果）
        if (mainAlpha > 0f && mainGlowRadius > 0f) {
            mainGlowPaint.setAlpha((int) (mainAlpha * 255));
            canvas.drawCircle(centerX, centerY, mainGlowRadius, mainGlowPaint);
        }

        // 2. 绘制波纹圆环
        for (Ripple ripple : ripples) {
            if (ripple.alpha > 0f && ripple.radius > 0f) {
                ripplePaint.setAlpha((int) (ripple.alpha * 255));
                ripplePaint.setStrokeWidth(4f * (1f - ripple.radius / maxGlowRadius) + 1f);
                canvas.drawCircle(centerX, centerY, ripple.radius, ripplePaint);
            }
        }

        // 3. 绘制中心脉冲（多层效果）
        if (coreScale > 0f && mainAlpha > 0f) {
            float baseRadius = maxGlowRadius * 0.15f * coreScale;

            // 内层高亮
            corePaint.setAlpha((int) (mainAlpha * 200));
            canvas.drawCircle(centerX, centerY, baseRadius, corePaint);

            // 中层光晕
            corePaint.setAlpha((int) (mainAlpha * 120));
            canvas.drawCircle(centerX, centerY, baseRadius * 1.5f, corePaint);

            // 外层微光
            corePaint.setAlpha((int) (mainAlpha * 60));
            canvas.drawCircle(centerX, centerY, baseRadius * 2f, corePaint);
        }
    }

    /**
     * 启动呼吸灯动画
     */
    public void startGlow() {
        if (isAnimating) {
            return;
        }

        isAnimating = true;
        setVisibility(VISIBLE);
        ripples.clear();
        lastRippleTime = System.currentTimeMillis();

        // 1. 主光晕动画 - 扩散 + 呼吸
        ObjectAnimator mainRadiusAnimator = ObjectAnimator.ofFloat(this, "mainGlowRadius", 0f, maxGlowRadius * 0.9f);
        mainRadiusAnimator.setDuration(1800);
        mainRadiusAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        mainRadiusAnimator.setRepeatMode(ObjectAnimator.RESTART);

        ObjectAnimator mainAlphaAnimator = ObjectAnimator.ofFloat(this, "mainAlpha", 0f, 0.8f, 0.4f, 0f);
        mainAlphaAnimator.setDuration(1800);
        mainAlphaAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        mainAlphaAnimator.setRepeatMode(ObjectAnimator.RESTART);

        // 2. 中心脉冲动画
        ObjectAnimator coreAnimator = ObjectAnimator.ofFloat(this, "coreScale", 0f, 1.2f, 0.8f, 0f);
        coreAnimator.setDuration(1800);
        coreAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        coreAnimator.setRepeatMode(ObjectAnimator.RESTART);

        // 组合主动画
        glowAnimatorSet = new AnimatorSet();
        glowAnimatorSet.playTogether(mainRadiusAnimator, mainAlphaAnimator, coreAnimator);
        glowAnimatorSet.setInterpolator(new DecelerateInterpolator());
        glowAnimatorSet.start();

        // 3. 波纹动画（独立运行，产生持续扩散效果）
        startRippleAnimation();
    }

    /**
     * 启动波纹动画
     */
    private void startRippleAnimation() {
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f);
        rippleAnimator.setDuration(50); // 高频更新
        rippleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rippleAnimator.addUpdateListener(animation -> {
            // 每400ms产生一个新的波纹
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRippleTime > 400) {
                addNewRipple();
                lastRippleTime = currentTime;
            }

            // 更新所有波纹
            updateRipples();
            invalidate();
        });
        rippleAnimator.start();
    }

    /**
     * 添加新波纹
     */
    private void addNewRipple() {
        float startRadius = maxGlowRadius * 0.1f;
        float startAlpha = 0.6f;
        float speed = maxGlowRadius * 0.003f; // 波纹扩散速度

        ripples.add(new Ripple(startRadius, startAlpha, speed));

        // 限制波纹数量
        if (ripples.size() > 8) {
            ripples.remove(0);
        }
    }

    /**
     * 更新所有波纹状态
     */
    private void updateRipples() {
        List<Ripple> toRemove = new ArrayList<>();

        for (Ripple ripple : ripples) {
            ripple.radius += ripple.speed;
            ripple.alpha -= 0.008f; // 渐隐

            if (ripple.alpha <= 0f || ripple.radius > maxGlowRadius * 1.2f) {
                toRemove.add(ripple);
            }
        }

        ripples.removeAll(toRemove);
    }

    /**
     * 停止呼吸灯动画
     */
    public void stopGlow() {
        if (!isAnimating) {
            return;
        }

        isAnimating = false;

        if (glowAnimatorSet != null && glowAnimatorSet.isRunning()) {
            glowAnimatorSet.cancel();
            glowAnimatorSet = null;
        }

        if (rippleAnimator != null && rippleAnimator.isRunning()) {
            rippleAnimator.cancel();
            rippleAnimator = null;
        }

        // 重置状态
        mainGlowRadius = 0f;
        mainAlpha = 0f;
        coreScale = 0f;
        ripples.clear();
        invalidate();
        setVisibility(GONE);
    }

    // Setter方法（用于动画）

    public void setMainGlowRadius(float radius) {
        this.mainGlowRadius = radius;
        invalidate();
    }

    public void setMainAlpha(float alpha) {
        this.mainAlpha = alpha;
        invalidate();
    }

    public void setCoreScale(float scale) {
        this.coreScale = scale;
        invalidate();
    }

    /**
     * 设置光晕颜色
     */
    public void setGlowColor(int color) {
        this.glowColor = color;
        updateMainGlowGradient();
        ripplePaint.setColor(color);
        corePaint.setColor(color);
        invalidate();
    }

    /**
     * 检查是否正在动画
     */
    public boolean isGlowing() {
        return isAnimating;
    }
}
