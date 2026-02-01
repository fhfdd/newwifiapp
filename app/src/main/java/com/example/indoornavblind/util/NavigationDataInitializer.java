package com.example.indoornavblind.util;

import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 导航数据初始化工具
 * 用于插入示例路径数据到数据库
 */
public class NavigationDataInitializer {
    
    /**
     * 初始化示例导航数据
     * 
     * 示例：从"教室A"到"图书馆"的路径
     */
    public static void initializeSampleData(NavigationNodeDao dao) {
        // 清空现有数据（可选）
        // dao.deleteAll();
        
        // 路径1: 教室A → 图书馆
        insertPath_ClassroomA_To_Library(dao);
        
        // 路径2: 图书馆 → 食堂
        insertPath_Library_To_Canteen(dao);
        
        // 路径3: 教室A → 食堂
        insertPath_ClassroomA_To_Canteen(dao);
    }
    
    /**
     * 插入路径：教室A → 图书馆
     * 
     * 路径说明：
     * 1. 从教室A出发，向东走10步
     * 2. 到达走廊，左转向北走15步
     * 3. 到达楼梯口，右转向东走8步
     * 4. 到达图书馆入口
     */
    private static void insertPath_ClassroomA_To_Library(NavigationNodeDao dao) {
        String pathId = "path_" + UUID.randomUUID().toString().substring(0, 8);
        List<NavigationNodeEntity> nodes = new ArrayList<>();
        
        // 节点0: 起点
        NavigationNodeEntity node0 = new NavigationNodeEntity();
        node0.setPathId(pathId);
        node0.setNodeIndex(0);
        node0.setStartLabel("教室A");
        node0.setEndLabel("图书馆");
        node0.setCumulativeSteps(0);
        node0.setSegmentSteps(0);
        node0.setSegmentDistance(0);
        node0.setDirection(90); // 东
        node0.setDirectionDesc_cn("东");
        node0.setDirectionDesc_en("East");
        node0.setDirectionDesc_yue("东");
        node0.setInstruction_cn("从教室A出发，面朝东方");
        node0.setInstruction_en("Start from Classroom A, facing east");
        node0.setInstruction_yue("由教室A出发，面向东方");
        node0.setDestination(false);
        node0.setFloor(1);
        node0.setPixelX(100);
        node0.setPixelY(100);
        nodes.add(node0);
        
        // 节点1: 走廊
        NavigationNodeEntity node1 = new NavigationNodeEntity();
        node1.setPathId(pathId);
        node1.setNodeIndex(1);
        node1.setStartLabel("教室A");
        node1.setEndLabel("图书馆");
        node1.setCumulativeSteps(10);
        node1.setSegmentSteps(10);
        node1.setSegmentDistance(6.5f); // 10步 × 0.65米
        node1.setDirection(0); // 北
        node1.setDirectionDesc_cn("北");
        node1.setDirectionDesc_en("North");
        node1.setDirectionDesc_yue("北");
        node1.setInstruction_cn("到达走廊，左转向北走15步");
        node1.setInstruction_en("Reach the corridor, turn left and walk north for 15 steps");
        node1.setInstruction_yue("到达走廊，左转向北行15步");
        node1.setDestination(false);
        node1.setFloor(1);
        node1.setPixelX(106.5);
        node1.setPixelY(100);
        nodes.add(node1);
        
        // 节点2: 楼梯口
        NavigationNodeEntity node2 = new NavigationNodeEntity();
        node2.setPathId(pathId);
        node2.setNodeIndex(2);
        node2.setStartLabel("教室A");
        node2.setEndLabel("图书馆");
        node2.setCumulativeSteps(25);
        node2.setSegmentSteps(15);
        node2.setSegmentDistance(9.75f); // 15步 × 0.65米
        node2.setDirection(90); // 东
        node2.setDirectionDesc_cn("东");
        node2.setDirectionDesc_en("East");
        node2.setDirectionDesc_yue("东");
        node2.setInstruction_cn("到达楼梯口，右转向东走8步");
        node2.setInstruction_en("Reach the stairs, turn right and walk east for 8 steps");
        node2.setInstruction_yue("到达楼梯口，右转向东行8步");
        node2.setDestination(false);
        node2.setFloor(1);
        node2.setPixelX(106.5);
        node2.setPixelY(109.75);
        nodes.add(node2);
        
        // 节点3: 图书馆（目的地）
        NavigationNodeEntity node3 = new NavigationNodeEntity();
        node3.setPathId(pathId);
        node3.setNodeIndex(3);
        node3.setStartLabel("教室A");
        node3.setEndLabel("图书馆");
        node3.setCumulativeSteps(33);
        node3.setSegmentSteps(8);
        node3.setSegmentDistance(5.2f); // 8步 × 0.65米
        node3.setDirection(90); // 东
        node3.setDirectionDesc_cn("东");
        node3.setDirectionDesc_en("East");
        node3.setDirectionDesc_yue("东");
        node3.setInstruction_cn("到达图书馆入口，导航结束");
        node3.setInstruction_en("Arrive at the library entrance, navigation complete");
        node3.setInstruction_yue("到达图书馆入口，导航完成");
        node3.setDestination(true);
        node3.setFloor(1);
        node3.setPixelX(111.7);
        node3.setPixelY(109.75);
        nodes.add(node3);
        
        // 批量插入
        dao.insertAll(nodes);
    }
    
    /**
     * 插入路径：图书馆 → 食堂
     */
    private static void insertPath_Library_To_Canteen(NavigationNodeDao dao) {
        String pathId = "path_" + UUID.randomUUID().toString().substring(0, 8);
        List<NavigationNodeEntity> nodes = new ArrayList<>();
        
        // 节点0: 起点（图书馆）
        NavigationNodeEntity node0 = new NavigationNodeEntity();
        node0.setPathId(pathId);
        node0.setNodeIndex(0);
        node0.setStartLabel("图书馆");
        node0.setEndLabel("食堂");
        node0.setCumulativeSteps(0);
        node0.setSegmentSteps(0);
        node0.setSegmentDistance(0);
        node0.setDirection(270); // 西
        node0.setDirectionDesc_cn("西");
        node0.setDirectionDesc_en("West");
        node0.setDirectionDesc_yue("西");
        node0.setInstruction_cn("从图书馆出发，面朝西方");
        node0.setInstruction_en("Start from the library, facing west");
        node0.setInstruction_yue("由图书馆出发，面向西方");
        node0.setDestination(false);
        node0.setFloor(1);
        node0.setPixelX(111.7);
        node0.setPixelY(109.75);
        nodes.add(node0);
        
        // 节点1: 大厅
        NavigationNodeEntity node1 = new NavigationNodeEntity();
        node1.setPathId(pathId);
        node1.setNodeIndex(1);
        node1.setStartLabel("图书馆");
        node1.setEndLabel("食堂");
        node1.setCumulativeSteps(20);
        node1.setSegmentSteps(20);
        node1.setSegmentDistance(13.0f);
        node1.setDirection(180); // 南
        node1.setDirectionDesc_cn("南");
        node1.setDirectionDesc_en("South");
        node1.setDirectionDesc_yue("南");
        node1.setInstruction_cn("到达大厅，左转向南走25步");
        node1.setInstruction_en("Reach the hall, turn left and walk south for 25 steps");
        node1.setInstruction_yue("到达大厅，左转向南行25步");
        node1.setDestination(false);
        node1.setFloor(1);
        node1.setPixelX(98.7);
        node1.setPixelY(109.75);
        nodes.add(node1);
        
        // 节点2: 食堂（目的地）
        NavigationNodeEntity node2 = new NavigationNodeEntity();
        node2.setPathId(pathId);
        node2.setNodeIndex(2);
        node2.setStartLabel("图书馆");
        node2.setEndLabel("食堂");
        node2.setCumulativeSteps(45);
        node2.setSegmentSteps(25);
        node2.setSegmentDistance(16.25f);
        node2.setDirection(180); // 南
        node2.setDirectionDesc_cn("南");
        node2.setDirectionDesc_en("South");
        node2.setDirectionDesc_yue("南");
        node2.setInstruction_cn("到达食堂入口，导航结束");
        node2.setInstruction_en("Arrive at the canteen entrance, navigation complete");
        node2.setInstruction_yue("到达食堂入口，导航完成");
        node2.setDestination(true);
        node2.setFloor(1);
        node2.setPixelX(98.7);
        node2.setPixelY(93.5);
        nodes.add(node2);
        
        dao.insertAll(nodes);
    }
    
    /**
     * 插入路径：教室A → 食堂
     */
    private static void insertPath_ClassroomA_To_Canteen(NavigationNodeDao dao) {
        String pathId = "path_" + UUID.randomUUID().toString().substring(0, 8);
        List<NavigationNodeEntity> nodes = new ArrayList<>();
        
        // 节点0: 起点
        NavigationNodeEntity node0 = new NavigationNodeEntity();
        node0.setPathId(pathId);
        node0.setNodeIndex(0);
        node0.setStartLabel("教室A");
        node0.setEndLabel("食堂");
        node0.setCumulativeSteps(0);
        node0.setSegmentSteps(0);
        node0.setSegmentDistance(0);
        node0.setDirection(180); // 南
        node0.setDirectionDesc_cn("南");
        node0.setDirectionDesc_en("South");
        node0.setDirectionDesc_yue("南");
        node0.setInstruction_cn("从教室A出发，面朝南方");
        node0.setInstruction_en("Start from Classroom A, facing south");
        node0.setInstruction_yue("由教室A出发，面向南方");
        node0.setDestination(false);
        node0.setFloor(1);
        node0.setPixelX(100);
        node0.setPixelY(100);
        nodes.add(node0);
        
        // 节点1: 走廊交叉口
        NavigationNodeEntity node1 = new NavigationNodeEntity();
        node1.setPathId(pathId);
        node1.setNodeIndex(1);
        node1.setStartLabel("教室A");
        node1.setEndLabel("食堂");
        node1.setCumulativeSteps(12);
        node1.setSegmentSteps(12);
        node1.setSegmentDistance(7.8f);
        node1.setDirection(270); // 西
        node1.setDirectionDesc_cn("西");
        node1.setDirectionDesc_en("West");
        node1.setDirectionDesc_yue("西");
        node1.setInstruction_cn("到达走廊交叉口，右转向西走18步");
        node1.setInstruction_en("Reach the corridor intersection, turn right and walk west for 18 steps");
        node1.setInstruction_yue("到达走廊交叉口，右转向西行18步");
        node1.setDestination(false);
        node1.setFloor(1);
        node1.setPixelX(100);
        node1.setPixelY(92.2);
        nodes.add(node1);
        
        // 节点2: 食堂（目的地）
        NavigationNodeEntity node2 = new NavigationNodeEntity();
        node2.setPathId(pathId);
        node2.setNodeIndex(2);
        node2.setStartLabel("教室A");
        node2.setEndLabel("食堂");
        node2.setCumulativeSteps(30);
        node2.setSegmentSteps(18);
        node2.setSegmentDistance(11.7f);
        node2.setDirection(270); // 西
        node2.setDirectionDesc_cn("西");
        node2.setDirectionDesc_en("West");
        node2.setDirectionDesc_yue("西");
        node2.setInstruction_cn("到达食堂入口，导航结束");
        node2.setInstruction_en("Arrive at the canteen entrance, navigation complete");
        node2.setInstruction_yue("到达食堂入口，导航完成");
        node2.setDestination(true);
        node2.setFloor(1);
        node2.setPixelX(88.3);
        node2.setPixelY(92.2);
        nodes.add(node2);
        
        dao.insertAll(nodes);
    }
    
    /**
     * 创建自定义路径
     * 
     * @param startLabel 起点标签
     * @param endLabel 终点标签
     * @param instructions 指令列表（每个元素包含：步数、方向、指令文本）
     */
    public static void createCustomPath(NavigationNodeDao dao, 
                                       String startLabel, 
                                       String endLabel,
                                       List<PathInstruction> instructions) {
        String pathId = "path_" + UUID.randomUUID().toString().substring(0, 8);
        List<NavigationNodeEntity> nodes = new ArrayList<>();
        
        int cumulativeSteps = 0;
        for (int i = 0; i < instructions.size(); i++) {
            PathInstruction instruction = instructions.get(i);
            
            NavigationNodeEntity node = new NavigationNodeEntity();
            node.setPathId(pathId);
            node.setNodeIndex(i);
            node.setStartLabel(startLabel);
            node.setEndLabel(endLabel);
            node.setCumulativeSteps(cumulativeSteps);
            node.setSegmentSteps(instruction.steps);
            node.setSegmentDistance(instruction.steps * 0.65f);
            node.setDirection(instruction.direction);
            node.setDirectionDesc_cn(getDirectionDesc(instruction.direction));
            node.setDirectionDesc_en(getDirectionDescEn(instruction.direction));
            node.setDirectionDesc_yue(getDirectionDesc(instruction.direction));
            node.setInstruction_cn(instruction.instruction_cn);
            node.setInstruction_en(instruction.instruction_en);
            node.setInstruction_yue(instruction.instruction_yue);
            node.setDestination(i == instructions.size() - 1);
            node.setFloor(instruction.floor);
            
            nodes.add(node);
            cumulativeSteps += instruction.steps;
        }
        
        dao.insertAll(nodes);
    }
    
    /**
     * 获取方向描述（中文）
     */
    private static String getDirectionDesc(float direction) {
        if (direction >= 337.5 || direction < 22.5) return "北";
        else if (direction >= 22.5 && direction < 67.5) return "东北";
        else if (direction >= 67.5 && direction < 112.5) return "东";
        else if (direction >= 112.5 && direction < 157.5) return "东南";
        else if (direction >= 157.5 && direction < 202.5) return "南";
        else if (direction >= 202.5 && direction < 247.5) return "西南";
        else if (direction >= 247.5 && direction < 292.5) return "西";
        else return "西北";
    }
    
    /**
     * 获取方向描述（英文）
     */
    private static String getDirectionDescEn(float direction) {
        if (direction >= 337.5 || direction < 22.5) return "North";
        else if (direction >= 22.5 && direction < 67.5) return "Northeast";
        else if (direction >= 67.5 && direction < 112.5) return "East";
        else if (direction >= 112.5 && direction < 157.5) return "Southeast";
        else if (direction >= 157.5 && direction < 202.5) return "South";
        else if (direction >= 202.5 && direction < 247.5) return "Southwest";
        else if (direction >= 247.5 && direction < 292.5) return "West";
        else return "Northwest";
    }
    
    /**
     * 路径指令类
     */
    public static class PathInstruction {
        public int steps;           // 步数
        public float direction;     // 方向（0-360度）
        public String instruction_cn;  // 中文指令
        public String instruction_en;  // 英文指令
        public String instruction_yue; // 粤语指令
        public int floor;           // 楼层
        
        public PathInstruction(int steps, float direction, 
                             String instruction_cn, String instruction_en, 
                             String instruction_yue, int floor) {
            this.steps = steps;
            this.direction = direction;
            this.instruction_cn = instruction_cn;
            this.instruction_en = instruction_en;
            this.instruction_yue = instruction_yue;
            this.floor = floor;
        }
    }
}
