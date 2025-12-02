package com.example.indoornavblind.service.impl;

import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.util.PathParser;
import java.util.List;
import java.util.Locale;

/**
 * 路径导航实现：基于预定义路径的导航，实现NavigationService接口
 */
public class L_PathNavigationService implements NavigationService {
    private Position currentPosition;
    private String target;
    private List<PathEntity> fullPath;
    private int currentStep = 0;
    private Locale currentLocale = Locale.CHINESE;

    @Override
    public void setCurrentPosition(Position position) {
        this.currentPosition = position;
    }

    @Override
    public void setTarget(String target) {
        this.target = target;
    }

    @Override
    public List<PathEntity> calculatePath() {
        if (currentPosition == null || target == null) return List.of();
        fullPath = PathParser.getFullPath(currentPosition.getLabel(), target);
        currentStep = 0;
        return fullPath;
    }

    @Override
    public String getNextStepInstruction() {
        if (fullPath == null || fullPath.isEmpty() || currentStep >= fullPath.size()) {
            return "已到达目的地";
        }
        PathEntity step = fullPath.get(currentStep);
        currentStep++;
        return String.format("第%d步：%s，%s",
                currentStep,
                PathParser.getDirectionByLang(step, currentLocale),
                PathParser.getDistanceByLang(step, currentLocale)
        );
    }

    public void setLocale(Locale locale) {
        this.currentLocale = locale;
    }
}
