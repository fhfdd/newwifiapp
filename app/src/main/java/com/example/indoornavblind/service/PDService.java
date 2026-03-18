package com.example.indoornavblind.service;

import android.content.Context;
import com.example.indoornavblind.model.Position;

public interface PDService {
    void init(Context context, Position initialPosition);
    Position updatePosition();
    void stop();
}