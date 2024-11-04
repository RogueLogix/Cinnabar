package net.roguelogix.phosphophyllite.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@NonnullDefault
public @interface RegisterTile {
    String modid() default "";
    
    String value();
    
    class Producer<T extends BlockEntity> implements BlockEntityType.BlockEntitySupplier<T> {
        public interface Constructor<T extends BlockEntity> {
    
            T apply(BlockEntityType<T> tileEntityTypeIn, BlockPos pos, BlockState state);
        }
        private final Constructor<T> constructor;
    
        @Nullable
        public final BlockEntityType<T> TYPE;
        {
            // set via reflection from registry
            TYPE = null;
        }
    
        public Producer(Constructor<T> constructor) {
            this.constructor = constructor;
        }
    
        @Override
        public T create(BlockPos pos, BlockState state) {
            assert TYPE != null;
            return constructor.apply(TYPE, pos, state);
        }
    }
}
