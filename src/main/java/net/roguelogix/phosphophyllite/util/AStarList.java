package net.roguelogix.phosphophyllite.util;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AStarList<TileType> {
    
    TreeSet<TileType> targets = new TreeSet<>(this::orderingFunction);
    TreeSet<TileType> nodeSet = new TreeSet<>(this::orderingFunction);
    
    final Function<TileType, BlockPos> posFunc;
    
    public AStarList(Function<TileType, BlockPos> posFunc){
        this.posFunc = posFunc;
    }
    
    private int orderingFunction(TileType a, TileType b) {
        if(targets.isEmpty()){
            return 0;
        }
        var aPos = posFunc.apply(a);
        var bPos = posFunc.apply(b);
        var target = posFunc.apply(targets.first());
        double aDistance = aPos.distSqr(target);
        double bDistance = bPos.distSqr(target);
        return aDistance < bDistance ? -1 : (bDistance == aDistance ? Integer.compare(a.hashCode(), b.hashCode()) : 1);
    }
    
    public void addTarget(TileType target) {
        if (nodeSet.isEmpty()) {
            nodeSet.add(target);
        } else {
            targets.add(target);
        }
    }
    
    public void addNode(TileType node) {
        targets.remove(node);
        nodeSet.add(node);
    }
    
    public TileType nextNode() {
        return Objects.requireNonNull(nodeSet.pollFirst());
    }
    
    public boolean done() {
        return targets.isEmpty() || nodeSet.isEmpty();
    }
    
    public boolean foundAll() {
        return targets.isEmpty();
    }
}
