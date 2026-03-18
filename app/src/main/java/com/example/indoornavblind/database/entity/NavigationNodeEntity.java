package com.example.indoornavblind.database.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 导航节点实体 - 用于存储每个路径节点的详细信息
 * 包含位置、步数、方向、播报时机等信息
 */
@Entity(tableName = "navigation_nodes")
public class NavigationNodeEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    // 路径标识（同一条路径的所有节点共享相同的pathId）
    private String pathId;
    
    // 节点序号（从0开始）
    private int nodeIndex;
    
    // 起点位置标签
    private String startLabel;
    
    // 终点位置标签
    private String endLabel;
    
    // 从起点到此节点需要的总步数
    private int cumulativeSteps;
    
    // 到达此节点后的播报指令（中文）
    private String instruction_cn;
    
    // 到达此节点后的播报指令（英文）
    private String instruction_en;
    
    // 到达此节点后的播报指令（粤语）
    private String instruction_yue;
    
    // 方向（0-360度，0为北，90为东，180为南，270为西）
    private float direction;
    
    // 方向描述（北/东/南/西/东北等）
    private String directionDesc_cn;
    private String directionDesc_en;
    private String directionDesc_yue;
    
    // 该段路径的步数（从上一个节点到此节点）
    private int segmentSteps;
    
    // 该段路径的距离（米）
    private float segmentDistance;
    
    // 是否为最终目的地
    private boolean isDestination;
    
    // 楼层
    private int floor;
    
    // 坐标（用于备用定位）
    private double pixelX;
    private double pixelY;

    // Getters and Setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPathId() {
        return pathId;
    }

    public void setPathId(String pathId) {
        this.pathId = pathId;
    }

    public int getNodeIndex() {
        return nodeIndex;
    }

    public void setNodeIndex(int nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    public String getStartLabel() {
        return startLabel;
    }

    public void setStartLabel(String startLabel) {
        this.startLabel = startLabel;
    }

    public String getEndLabel() {
        return endLabel;
    }

    public void setEndLabel(String endLabel) {
        this.endLabel = endLabel;
    }

    public int getCumulativeSteps() {
        return cumulativeSteps;
    }

    public void setCumulativeSteps(int cumulativeSteps) {
        this.cumulativeSteps = cumulativeSteps;
    }

    public String getInstruction_cn() {
        return instruction_cn;
    }

    public void setInstruction_cn(String instruction_cn) {
        this.instruction_cn = instruction_cn;
    }

    public String getInstruction_en() {
        return instruction_en;
    }

    public void setInstruction_en(String instruction_en) {
        this.instruction_en = instruction_en;
    }

    public String getInstruction_yue() {
        return instruction_yue;
    }

    public void setInstruction_yue(String instruction_yue) {
        this.instruction_yue = instruction_yue;
    }

    public float getDirection() {
        return direction;
    }

    public void setDirection(float direction) {
        this.direction = direction;
    }

    public String getDirectionDesc_cn() {
        return directionDesc_cn;
    }

    public void setDirectionDesc_cn(String directionDesc_cn) {
        this.directionDesc_cn = directionDesc_cn;
    }

    public String getDirectionDesc_en() {
        return directionDesc_en;
    }

    public void setDirectionDesc_en(String directionDesc_en) {
        this.directionDesc_en = directionDesc_en;
    }

    public String getDirectionDesc_yue() {
        return directionDesc_yue;
    }

    public void setDirectionDesc_yue(String directionDesc_yue) {
        this.directionDesc_yue = directionDesc_yue;
    }

    public int getSegmentSteps() {
        return segmentSteps;
    }

    public void setSegmentSteps(int segmentSteps) {
        this.segmentSteps = segmentSteps;
    }

    public float getSegmentDistance() {
        return segmentDistance;
    }

    public void setSegmentDistance(float segmentDistance) {
        this.segmentDistance = segmentDistance;
    }

    public boolean isDestination() {
        return isDestination;
    }

    public void setDestination(boolean destination) {
        isDestination = destination;
    }

    public int getFloor() {
        return floor;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }

    public double getPixelX() {
        return pixelX;
    }

    public void setPixelX(double pixelX) {
        this.pixelX = pixelX;
    }

    public double getPixelY() {
        return pixelY;
    }

    public void setPixelY(double pixelY) {
        this.pixelY = pixelY;
    }
}
