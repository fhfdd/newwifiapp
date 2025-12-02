package com.example.indoornavblind.service;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import com.example.indoornavblind.model.Position;

public class L_PDServiceImpl implements PDService, SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Position lastPosition;
    private float stepLength = 0.65f;
    private int stepCount = 0;
    private float direction = 0;

    @Override
    public void init(Context context, Position initialPosition) {
        this.lastPosition = initialPosition;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public Position updatePosition() {
        double rad = Math.toRadians(direction);
        double dx = stepCount * stepLength * Math.cos(rad);
        double dy = stepCount * stepLength * Math.sin(rad);
        lastPosition.setPixelX(lastPosition.getPixelX() + dx);
        lastPosition.setPixelY(lastPosition.getPixelY() + dy);
        stepCount = 0;
        return lastPosition;
    }

    @Override
    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float y = event.values[1];
            if (y > 12) stepCount++;
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            direction += event.values[2] * 0.1;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}