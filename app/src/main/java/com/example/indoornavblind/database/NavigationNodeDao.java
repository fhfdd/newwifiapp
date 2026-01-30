package com.example.indoornavblind.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Delete;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import java.util.List;

/**
 * 导航节点DAO - 数据库访问接口
 */
@Dao
public interface NavigationNodeDao {
    
    /**
     * 插入导航节点
     */
    @Insert
    void insert(NavigationNodeEntity node);
    
    /**
     * 批量插入导航节点
     */
    @Insert
    void insertAll(List<NavigationNodeEntity> nodes);
    
    /**
     * 删除导航节点
     */
    @Delete
    void delete(NavigationNodeEntity node);
    
    /**
     * 根据pathId删除整条路径的所有节点
     */
    @Query("DELETE FROM navigation_nodes WHERE pathId = :pathId")
    void deleteByPathId(String pathId);
    
    /**
     * 查询指定路径的所有节点（按节点序号排序）
     */
    @Query("SELECT * FROM navigation_nodes WHERE pathId = :pathId ORDER BY nodeIndex ASC")
    List<NavigationNodeEntity> getNodesByPathId(String pathId);
    
    /**
     * 根据起点和终点查询路径节点
     */
    @Query("SELECT * FROM navigation_nodes WHERE startLabel = :start AND endLabel = :end ORDER BY nodeIndex ASC")
    List<NavigationNodeEntity> getNodesByStartAndEnd(String start, String end);
    
    /**
     * 获取指定路径的特定节点
     */
    @Query("SELECT * FROM navigation_nodes WHERE pathId = :pathId AND nodeIndex = :index")
    NavigationNodeEntity getNodeByIndex(String pathId, int index);
    
    /**
     * 查询所有导航节点
     */
    @Query("SELECT * FROM navigation_nodes")
    List<NavigationNodeEntity> getAllNodes();
    
    /**
     * 清空所有导航节点
     */
    @Query("DELETE FROM navigation_nodes")
    void deleteAll();
    
    /**
     * 根据累计步数查询最近的节点
     */
    @Query("SELECT * FROM navigation_nodes WHERE pathId = :pathId AND cumulativeSteps <= :currentSteps ORDER BY cumulativeSteps DESC LIMIT 1")
    NavigationNodeEntity getNearestNodeBySteps(String pathId, int currentSteps);
}
