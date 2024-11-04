package net.roguelogix.phosphophyllite.registry;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.roguelogix.phosphophyllite.util.Pair;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class CapabilityRegistration {
    
    public static <O extends T, T, C> CapabilityRegistration tileCap(BlockCapability<T, C> capability, Class<O> tileClass) {
        if (!BlockEntity.class.isAssignableFrom(tileClass)) {
            throw new IllegalArgumentException("Automatic tile entity capability must have class extending from BlockEntity");
        }
        ICapabilityProvider<O, C, T> provider = (o, c) -> (T) o;
        
        return new TileCapRegistration<>(capability, tileClass, provider);
    }
    
    public static <O, T, C> CapabilityRegistration tileCap(BlockCapability<T, C> capability, Class<O> tileClass, Function<O, T> capabilityProvider) {
        return tileCap(capability, tileClass, (O o, C c) -> capabilityProvider.apply(o));
    }
    
    public static <O, T, C> CapabilityRegistration tileCap(BlockCapability<T, C> capability, Class<O> tileClass, ICapabilityProvider<O, C, T> capabilityProvider) {
        return new TileCapRegistration<>(capability, tileClass, capabilityProvider);
    }
    
    public static <O> CapabilityRegistration lazyTileCaps(Class<O> tileClass, Supplier<List<Pair<BlockCapability<?, ?>, ICapabilityProvider<O, ?, ?>>>> capsSupplier) {
        return new TileCapRegistration.Lazy<>(tileClass, capsSupplier);
    }
    
    private CapabilityRegistration() {
    }
    
    static class TileCapRegistration<O, T, C> extends CapabilityRegistration {
        final BlockCapability<T, C> capability;
        final Class<O> tileClass;
        final ICapabilityProvider<O, C, T> capabilityProvider;
        
        TileCapRegistration(BlockCapability<T, C> capability, Class<O> tileClass, ICapabilityProvider<O, C, T> capabilityProvider) {
            this.capability = capability;
            this.tileClass = tileClass;
            this.capabilityProvider = capabilityProvider;
        }
        
        static class Lazy<O> extends CapabilityRegistration {
            final Class<O> tileClass;
            final Supplier<List<Pair<BlockCapability<?, ?>, ICapabilityProvider<O, ?, ?>>>> supplier;
            
            Lazy(Class<O> tileClass, Supplier<List<Pair<BlockCapability<?, ?>, ICapabilityProvider<O, ?, ?>>>> supplier) {
                this.tileClass = tileClass;
                this.supplier = supplier;
            }
            
            List<TileCapRegistration<O, ?, ?>> registrations() {
                var pairs = supplier.get();
                var registrations = new ReferenceArrayList<TileCapRegistration<O, ?, ?>>();
                for (var pair : pairs) {
                    //noinspection unchecked
                    registrations.add(new TileCapRegistration<>((BlockCapability<Object, Object>)pair.first(), tileClass, (ICapabilityProvider<O, Object, Object>)pair.second()));
                }
                return registrations;
            }
        }
    }
}
