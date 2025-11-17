package com.example.indoornavblind.service;

import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import java.util.List;

/**
 * 导航服务接口：抽象导航能力，可扩展不同路径规划算法
 */
public interface NavigationService {
    void setCurrentPosition(Position position);
    void setTarget(String target);
    List<PathEntity> calculatePath();
    String getNextStepInstruction();
}