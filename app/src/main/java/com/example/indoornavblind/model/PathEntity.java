package com.example.indoornavblind.model;

public class PathEntity {
    // 多语言起点标签
    private String startLabel_cn;
    private String startLabel_en;
    private String startLabel_yue;
    // 多语言终点标签
    private String endLabel_cn;
    private String endLabel_en;
    private String endLabel_yue;
    // 楼层（支持跨层，如"1→2"）
    private int floor;
    // 优先级（数字越小，路径越优先）
    private int priority;
    // 多语言距离
    private String distance_cn;
    private String distance_en;
    private String distance_yue;
    // 多语言方向
    private String direction_cn;
    private String direction_en;
    private String direction_yue;
    // 多语言下一个关键点
    private String nextPoint_cn;
    private String nextPoint_en;
    private String nextPoint_yue;

    public String getStartLabel_cn() { return startLabel_cn; }
    public void setStartLabel_cn(String startLabel_cn) { this.startLabel_cn = startLabel_cn; }
    public String getStartLabel_en() { return startLabel_en; }
    public void setStartLabel_en(String startLabel_en) { this.startLabel_en = startLabel_en; }
    public String getStartLabel_yue() { return startLabel_yue; }
    public void setStartLabel_yue(String startLabel_yue) { this.startLabel_yue = startLabel_yue; }

    public String getEndLabel_cn() { return endLabel_cn; }
    public void setEndLabel_cn(String endLabel_cn) { this.endLabel_cn = endLabel_cn; }
    public String getEndLabel_en() { return endLabel_en; }
    public void setEndLabel_en(String endLabel_en) { this.endLabel_en = endLabel_en; }
    public String getEndLabel_yue() { return endLabel_yue; }
    public void setEndLabel_yue(String endLabel_yue) { this.endLabel_yue = endLabel_yue; }

    public int getFloor() { return floor; }

    public void setFloor(int floor) { this.floor = floor; }
    public int getPriority() { return priority; }
    private String cardinal;
    private float bearing;
    public void setPriority(int priority) { this.priority = priority; }

    public String getCardinal() { return cardinal; }
    public void setCardinal(String cardinal) { this.cardinal = cardinal; }
    public float getBearing() { return bearing; }
    public void setBearing(float bearing) { this.bearing = bearing; }

    public String getDistance_cn() { return distance_cn; }
    public void setDistance_cn(String distance_cn) { this.distance_cn = distance_cn; }
    public String getDistance_en() { return distance_en; }
    public void setDistance_en(String distance_en) { this.distance_en = distance_en; }
    public String getDistance_yue() { return distance_yue; }
    public void setDistance_yue(String distance_yue) { this.distance_yue = distance_yue; }

    public String getDirection_cn() { return direction_cn; }
    public void setDirection_cn(String direction_cn) { this.direction_cn = direction_cn; }
    public String getDirection_en() { return direction_en; }
    public void setDirection_en(String direction_en) { this.direction_en = direction_en; }
    public String getDirection_yue() { return direction_yue; }
    public void setDirection_yue(String direction_yue) { this.direction_yue = direction_yue; }

    public String getNextPoint_cn() { return nextPoint_cn; }
    public void setNextPoint_cn(String nextPoint_cn) { this.nextPoint_cn = nextPoint_cn; }
    public String getNextPoint_en() { return nextPoint_en; }
    public void setNextPoint_en(String nextPoint_en) { this.nextPoint_en = nextPoint_en; }
    public String getNextPoint_yue() { return nextPoint_yue; }
    public void setNextPoint_yue(String nextPoint_yue) { this.nextPoint_yue = nextPoint_yue; }

    /**
     * 获取距离数值（米）
     */
    public double getDistanceMeters() {
        try {
            String numStr = distance_cn.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numStr);
        } catch (Exception e) {
            return 3.0; // 默认3米
        }
    }

    /**
     * 根据步长计算所需步数
     */
    public int getStepsRequired(double stepLength) {
        return (int) Math.ceil(getDistanceMeters() / stepLength);
    }





}