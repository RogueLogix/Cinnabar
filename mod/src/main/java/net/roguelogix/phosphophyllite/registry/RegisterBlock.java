package net.roguelogix.phosphophyllite.registry;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RegisterBlock {
    
    String modid() default "";
    
    String name();
    
    boolean registerItem() default true;
    
    boolean creativeTab() default true;
    
    Class<? extends BlockEntity> tileEntityClass() default BlockEntity.class;
}
