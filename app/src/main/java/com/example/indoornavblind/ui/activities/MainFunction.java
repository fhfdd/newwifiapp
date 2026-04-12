package com.example.indoornavblind.ui.activities;

import android.content.Context;

/**
 * 功能接口：所有业务功能（定位、导航等）需实现此接口
 * 遵循开闭原则：新增功能只需实现接口，无需修改现有代码
 */
public interface MainFunction {
    // 执行功能
    void execute(Context context);
    // 获取功能名称（多语言支持）
    String getFunctionName();
    // 扩展点：功能是否可执行（新增，用于前置条件判断）
    boolean isExecutable(Context context);
}