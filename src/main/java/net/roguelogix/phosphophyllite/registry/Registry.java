package net.roguelogix.phosphophyllite.registry;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.asm.RuntimeDistCleaner;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.roguelogix.phosphophyllite.config.ConfigManager;
import net.roguelogix.phosphophyllite.threading.WorkQueue;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.lang.annotation.ElementType;
import java.lang.reflect.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@NonnullDefault
@SuppressWarnings("StringConcatenationArgumentToLogCall")
public class Registry {
    
    private static final boolean IS_DEV_ENVIRONMENT = SharedConstants.IS_RUNNING_IN_IDE;
    
    private final Logger LOGGER;
    
    private final WorkQueue blockRegistrationQueue = new WorkQueue(true);
    
    @Nullable
    private RegisterEvent.RegisterHelper<Block> blockRegistryEvent;
    
    private final WorkQueue itemRegistrationQueue = new WorkQueue(true);
    @Nullable
    private RegisterEvent.RegisterHelper<Item> itemRegistryEvent;
    
    private final ResourceLocation creativeTabResourceLocation;
    private final Component creativeTabTitle;
    private Item itemGroupItem = Items.STONE;
    private final List<ResourceLocation> tabsBefore;
    private final List<ResourceLocation> tabsAfter;
    private final ObjectArrayList<Item> creativeTabItems = new ObjectArrayList<>();
    
    private final WorkQueue containerRegistrationQueue = new WorkQueue(true);
    @Nullable
    private RegisterEvent.RegisterHelper<MenuType<?>> containerRegistryEvent;
    
    private final WorkQueue tileRegistrationQueue = new WorkQueue(true);
    @Nullable
    private RegisterEvent.RegisterHelper<BlockEntityType<?>> tileRegistryEvent;
    private final Map<Class<?>, LinkedList<Block>> tileBlocks = new HashMap<>();
    
    private final WorkQueue registerCapabilityQueue = new WorkQueue(true);
    @Nullable
    private RegisterCapabilitiesEvent registerCapabilitiesEvent;
    
    private final Map<String, AnnotationHandler> annotationMap = new Object2ObjectOpenHashMap<>();
    
    {
        annotationMap.put(RegisterBlock.class.getName(), this::registerBlockAnnotation);
        annotationMap.put(RegisterItem.class.getName(), this::registerItemAnnotation);
        annotationMap.put(RegisterContainer.class.getName(), this::registerContainerAnnotation);
        annotationMap.put(RegisterTile.class.getName(), this::registerTileAnnotation);
    }
    
    private static String getCallerModId() {
        String callerClass = new Exception().getStackTrace()[2].getClassName();
        String callerPackage = callerClass.substring(0, callerClass.lastIndexOf("."));
        return callerPackage.substring(callerPackage.lastIndexOf(".") + 1);
    }
    
    public Registry(@Nonnull List<ResourceLocation> tabsBefore, @Nonnull List<ResourceLocation> tabsAfter) {
        this(tabsBefore, tabsAfter, getCallerModId());
    }
    
    public Registry(@Nonnull List<ResourceLocation> tabsBefore, @Nonnull List<ResourceLocation> tabsAfter, String modid) {
        String callerClass = new Exception().getStackTrace()[1].getClassName();
        if(callerClass.equals(Registry.class.getName())){
            callerClass = new Exception().getStackTrace()[2].getClassName();
        }
        String callerPackage = callerClass.substring(0, callerClass.lastIndexOf(modid) + modid.length() + 1);
        ModFileScanData modFileScanData = FMLLoader.getLoadingModList().getModFileById(modid).getFile().getScanResult();
        
        LOGGER = LogManager.getLogger("Phosphophyllite/Registry/" + modid);
        
        creativeTabResourceLocation = ResourceLocation.fromNamespaceAndPath(modid, "creative_tab");
        creativeTabTitle = Component.translatable("item_group." + modid);
        tabsBefore.add(CreativeModeTabs.SPAWN_EGGS.location());
        this.tabsBefore = tabsBefore;
        this.tabsAfter = tabsAfter;
        
        final var ignoredPackages = new ObjectArrayList<String>();
        
        final var ignoreRegistrationAnnotationClassName = IgnoreRegistration.class.getName();
        final var clientOnlyAnnotationClassName = ClientOnly.class.getName();
        for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
            final var annotationClassName = annotation.annotationType().getClassName();
            if (clientOnlyAnnotationClassName.equals(annotationClassName)) {
                if (FMLEnvironment.dist.isClient()) {
                    continue;
                }
            } else if (!ignoreRegistrationAnnotationClassName.equals(annotationClassName)) {
                continue;
            }
            if (annotation.targetType() != ElementType.TYPE) {
                continue;
            }
            if (!annotation.clazz().getClassName().endsWith("package-info")) {
                continue;
            }
            
            final var ignoreInDevInfo = (Boolean) annotation.annotationData().get("ignoreInDev");
            if (IS_DEV_ENVIRONMENT && (ignoreInDevInfo != null && !ignoreInDevInfo)) {
                continue;
            }
            final var className = annotation.clazz().getClassName();
            final var packageName = className.substring(0, className.lastIndexOf('.') + 1);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Package " + packageName + " set to ignored");
            }
            ignoredPackages.add(packageName);
        }
        
        final var ignoredTypes = new ObjectArrayList<String>();
        
        if (!FMLEnvironment.dist.isClient()) {
            for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
                // sided checks must be done before classload, as that itself may be problematic
                if (clientOnlyAnnotationClassName.equals(annotation.annotationType().getClassName())) {
                    final var className = annotation.clazz().getClassName();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Type " + className + " set to ignored from @ClientOnly");
                    }
                    ignoredTypes.add(className);
                }
            }
        }
        
        // these two are special cases that need to be handled first
        // in case anything needs config options during static construction
        handleAnnotationTypes(modFileScanData, callerPackage, modid, Map.of(RegisterConfig.class.getName(), this::registerConfigAnnotation), true, ignoredPackages, ignoredTypes);
        // this is used for module registration, which need to happen before block registration
        handleAnnotationTypes(modFileScanData, callerPackage, modid, Map.of(OnModLoad.class.getName(), this::onModLoadAnnotation), true, ignoredPackages, ignoredTypes);
        
        handleAnnotationTypes(modFileScanData, callerPackage, modid, annotationMap, false, ignoredPackages, ignoredTypes);
        
        
        IEventBus modBus = ModLoadingContext.get().getActiveContainer().getEventBus();
        
        modBus.addListener(this::registerEvent);
        
        modBus.addListener(this::registerCapabilitiesEventHandler);
    }
    
    private void handleAnnotationTypes(ModFileScanData modFileScanData, String callerPackage, String modNamespace, Map<String, AnnotationHandler> annotations, boolean requiredCheck, ObjectArrayList<String> ignoredPackages, ObjectArrayList<String> ignoredTypes) {
        annotations:
        for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
            final var annotationClassName = annotation.annotationType().getClassName();
            AnnotationHandler handler = annotations.get(annotationClassName);
            if (handler == null) {
                // not an annotation i handle
                continue;
            }
            String className = annotation.clazz().getClassName();
            if (className.startsWith(callerPackage)) {
                for (String ignoredPackage : ignoredPackages) {
                    if (className.startsWith(ignoredPackage)) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Ignoring " + annotationClassName + " in class " + className + " on member " + annotation.memberName() + " as package " + ignoredPackage + " is set to ignored");
                        }
                        continue annotations;
                    }
                }
                for (String ignoredType : ignoredTypes) {
                    if (className.equals(ignoredType)) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Ignoring " + annotationClassName + " in class " + className + " on member " + annotation.memberName() + " as type is set to ignored");
                        }
                        continue annotations;
                    }
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Attempting to handle " + annotationClassName + " in class " + className + " on member " + annotation.memberName());
                }
                try {
                    
                    Class<?> clazz = Registry.class.getClassLoader().loadClass(className);
                    // class loaded, so, pass it off to the handler
                    handler.run(modNamespace, clazz, annotation.memberName());
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Failed to handle annotation " + annotation.annotationType().getClassName() + " in class " + className + " on member " + annotation.memberName() + " with error " + e);
                    }
                    if (requiredCheck) {
                        var isRequired = annotation.annotationData().get("required");
                        if (isRequired == null) {
                            isRequired = Boolean.TRUE;
                        }
                        if (isRequired instanceof Boolean && (Boolean) isRequired) {
                            e.printStackTrace();
                            throw new IllegalStateException("Failed to handle required annotation " + annotation.annotationType().getClassName() + " in class " + className);
                        }
                    }
                } catch (RuntimeException e) {
                    if (e.getStackTrace()[0].getClassName().equals(RuntimeDistCleaner.class.getName())) {
                        throw new RuntimeException("RuntimeDistCleaner triggered when processing " + annotationClassName + " in " + className + ". Missing @ClientOnly or dist checks?", e);
                    }
                    throw e;
                }
            }
        }
    }
    
    private void creativeTabEvent(RegisterEvent.RegisterHelper<CreativeModeTab> event) {
        if (creativeTabItems.isEmpty()) {
            return;
        }
        final var tabBuilder = CreativeModeTab.builder();
        tabsBefore.forEach(tabBuilder::withTabsBefore);
        tabsAfter.forEach(tabBuilder::withTabsAfter);
        tabBuilder.title(creativeTabTitle);
        tabBuilder.icon(() -> new ItemStack(itemGroupItem));
        tabBuilder.displayItems((CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) -> {
            creativeTabItems.forEach(output::accept);
        });
        final var tab = tabBuilder.build();
        event.register(creativeTabResourceLocation, tab);
    }
    
    private void registerEvent(RegisterEvent registerEvent) {
        registerEvent.register(Registries.BLOCK, this::blockRegistration);
        registerEvent.register(Registries.ITEM, this::itemRegistration);
        registerEvent.register(Registries.MENU, this::containerRegistration);
        registerEvent.register(Registries.BLOCK_ENTITY_TYPE, this::tileEntityRegistration);
        registerEvent.register(Registries.CREATIVE_MODE_TAB, this::creativeTabEvent);
    }
    
    private void blockRegistration(RegisterEvent.RegisterHelper<Block> event) {
        blockRegistryEvent = event;
        blockRegistrationQueue.runAll();
        blockRegistryEvent = null;
    }
    
    private void itemRegistration(RegisterEvent.RegisterHelper<Item> event) {
        itemRegistryEvent = event;
        itemRegistrationQueue.runAll();
        itemRegistryEvent = null;
    }
    
    private void containerRegistration(RegisterEvent.RegisterHelper<MenuType<?>> containerTypeRegistryEvent) {
        containerRegistryEvent = containerTypeRegistryEvent;
        containerRegistrationQueue.runAll();
        containerRegistryEvent = null;
    }
    
    private void tileEntityRegistration(RegisterEvent.RegisterHelper<BlockEntityType<?>> tileEntityTypeRegister) {
        tileRegistryEvent = tileEntityTypeRegister;
        tileRegistrationQueue.runAll();
        tileRegistryEvent = null;
    }
    
    private void registerCapabilitiesEventHandler(RegisterCapabilitiesEvent event) {
        registerCapabilitiesEvent = event;
        registerCapabilityQueue.runAll();
        registerCapabilitiesEvent = null;
    }
    
    private interface AnnotationHandler {
        void run(final String modNamespace, final Class<?> clazz, final String memberName);
    }
    
    private void registerBlockAnnotation(final String modNamespace, final Class<?> declaringClass, final String memberName) {
        if (declaringClass.isAnnotationPresent(IgnoreRegistration.class)) {
            if (!IS_DEV_ENVIRONMENT || declaringClass.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Registration of block at " + memberName + " in " + declaringClass.getName() + " ignored");
                }
                return;
            }
        }
        
        blockRegistrationQueue.enqueueUntracked(() -> {
            final RegisterBlock annotation;
            final Object fieldObject;
            final Field field;
            try {
                field = declaringClass.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (!IS_DEV_ENVIRONMENT || field.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Registration of block at " + memberName + " in " + declaringClass.getName() + " ignored");
                        }
                        return;
                    }
                }
                if (!Modifier.isStatic(field.getModifiers())) {
                    LOGGER.warn("Non-static block instance variable " + memberName + " in " + declaringClass.getName());
                    return;
                }
                field.setAccessible(true);
                fieldObject = field.get(null);
                annotation = field.getAnnotation(RegisterBlock.class);
                
                if (!Modifier.isFinal(field.getModifiers())) {
                    LOGGER.warn("Non-final block instance variable " + memberName + " in " + declaringClass.getName());
                }
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find block field for block " + memberName + " in " + declaringClass.getName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access block field for block " + memberName + " in " + declaringClass.getName());
                return;
            }
            
            if (fieldObject == null) {
                LOGGER.warn("Null block instance variable " + memberName + " in " + declaringClass.getName());
                return;
            }
            
            final var modid = annotation.modid().isEmpty() ? modNamespace : annotation.modid();
            final var name = annotation.name();
            if (modid.isEmpty()) {
                LOGGER.error("Unable to register block without a name from class " + declaringClass.getName());
                return;
            }
            
            if (!(fieldObject instanceof final Block block)) {
                LOGGER.error("Attempt to register block from class not extended from Block. " + declaringClass.getName());
                return;
            }
            
            final var registryName = modid + ":" + name;
            
            if (annotation.tileEntityClass() != BlockEntity.class) {
                tileBlocks.computeIfAbsent(annotation.tileEntityClass(), k -> new LinkedList<>()).add(block);
            }
            
            blockRegistryEvent.register(ResourceLocation.parse(registryName), block);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Block registered: " + registryName);
            }
            
            if (annotation.registerItem()) {
                boolean creativeTabBlock = field.isAnnotationPresent(CreativeTabBlock.class);
                itemRegistrationQueue.enqueueUntracked(() -> {
                    var item = new BlockItem(block, new Item.Properties());
                    if (annotation.creativeTab()) {
                        creativeTabItems.add(item);
                    }
                    itemRegistryEvent.register(ResourceLocation.parse(registryName), item);
                    if (creativeTabBlock) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Creative tab item set as " + registryName + " for mod " + modNamespace);
                        }
                        itemGroupItem = item;
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("BlockItem registered for " + registryName);
                    }
                });
            }
        });
    }
    
    private void registerItemAnnotation(String modNamespace, Class<?> declaringClass, final String memberName) {
        if (declaringClass.isAnnotationPresent(IgnoreRegistration.class)) {
            if (!IS_DEV_ENVIRONMENT || declaringClass.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Registration of item at " + memberName + " in " + declaringClass.getName() + " ignored");
                }
                return;
            }
        }
        
        itemRegistrationQueue.enqueueUntracked(() -> {
            
            final Object fieldObject;
            final RegisterItem annotation;
            try {
                final Field field = declaringClass.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (!IS_DEV_ENVIRONMENT || field.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Registration of item at " + memberName + " in " + declaringClass.getName() + " ignored");
                        }
                        return;
                    }
                }
                field.setAccessible(true);
                fieldObject = field.get(null);
                annotation = field.getAnnotation(RegisterItem.class);
                
                if (!Modifier.isFinal(field.getModifiers())) {
                    LOGGER.warn("Non-final item instance variable " + memberName + " in " + declaringClass.getName());
                }
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find item field for block " + memberName + " in " + declaringClass.getName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access item field for block " + memberName + " in " + declaringClass.getName());
                return;
            }
            
            if (fieldObject == null) {
                LOGGER.warn("Null item instance variable " + memberName + " in " + declaringClass.getName());
                return;
            }
            
            if (!(fieldObject instanceof final Item item)) {
                LOGGER.error("Attempt to register item from class not extended from Item. " + declaringClass.getName());
                return;
            }
            
            final var modid = annotation.modid().isEmpty() ? modNamespace : annotation.modid();
            final var name = annotation.name();
            if (name.isEmpty()) {
                LOGGER.error("Unable to register item " + memberName + " in " + declaringClass.getName() + " without a registry name");
                return;
            }
            
            final String registryName = modid + ":" + name;
            
            if (annotation.creativeTab()) {
                creativeTabItems.add(item);
            }
            
            itemRegistryEvent.register(ResourceLocation.parse(registryName), item);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Item registered: " + registryName);
            }
        });
    }
    
//    private void registerFluidAnnotation(String modNamespace, Class<?> fluidClazz, final String memberName) {
//        if (fluidClazz.isAnnotationPresent(IgnoreRegistration.class)) {
//            if (!IS_DEV_ENVIRONMENT || fluidClazz.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
//                if (LOGGER.isDebugEnabled()) {
//                    LOGGER.debug("Registration of fluid at " + memberName + " in " + fluidClazz.getName() + " ignored");
//                }
//                return;
//            }
//        }
//
//        fluidRegistrationQueue.enqueueUntracked(() -> {
//            assert fluidClazz.isAnnotationPresent(RegisterFluid.class);
//
//            final RegisterFluid annotation = fluidClazz.getAnnotation(RegisterFluid.class);
//
//            String modid = annotation.modid().equals("") ? modNamespace : annotation.modid();
//            String name = annotation.name();
//            if (modid.equals("")) {
//                LOGGER.error("Unable to register fluid without a name");
//                return;
//            }
//
//
//            if (!BaseFlowingFluid.class.isAssignableFrom(fluidClazz)) {
//                LOGGER.error("Attempt to register fluid from class not extended from PhosphophylliteFluid");
//                return;
//            }
//
//            final String baseRegistryName = modid + ":" + name;
//            final var baseResourceLocation = ResourceLocation.parse(baseRegistryName);
//
//            PhosphophylliteFluid[] fluids = new PhosphophylliteFluid[2];
//            Item[] bucketArray = new Item[1];
//            LiquidBlock[] blockArray = new LiquidBlock[1];
//
//            Constructor<?> constructor;
//            try {
//                constructor = fluidClazz.getDeclaredConstructor(BaseFlowingFluid.Properties.class);
//            } catch (NoSuchMethodException e) {
//                LOGGER.error("Failed to find constructor to create instance of " + fluidClazz.getSimpleName());
//                return;
//            }
//
//            Supplier<? extends PhosphophylliteFluid> stillSupplier = () -> fluids[0];
//            Supplier<? extends PhosphophylliteFluid> flowingSupplier = () -> fluids[1];
//            final var fluidType = new FluidType(FluidType.Properties.create()) {
//                @Override
//                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
//                    consumer.accept(new IClientFluidTypeExtensions() {
//                        final ResourceLocation stillTexture = ResourceLocation.fromNamespaceAndPath(modid, "block/fluid/" + name + "_still");
//                        final ResourceLocation flowingTexture = ResourceLocation.fromNamespaceAndPath(modid, "block/fluid/" + name + "_flowing");
//                        final ResourceLocation overlayTexture = ResourceLocation.fromNamespaceAndPath(modid, "block/fluid/" + name + "_overlay");
//
//                        @Override
//                        public ResourceLocation getStillTexture() {
//                            return stillTexture;
//                        }
//
//                        @Override
//                        public ResourceLocation getFlowingTexture() {
//                            return flowingTexture;
//                        }
//
//                        @Override
//                        public ResourceLocation getOverlayTexture() {
//                            return overlayTexture;
//                        }
//
//                        @Override
//                        public int getTintColor() {
//                            return annotation.color();
//                        }
//                    });
//                }
//            };
//
//            BaseFlowingFluid.Properties properties = new BaseFlowingFluid.Properties(() -> fluidType, stillSupplier, flowingSupplier);
//            if (annotation.registerBucket()) {
//                properties.bucket(() -> bucketArray[0]);
//            }
//            properties.block(() -> blockArray[0]);
//
//            PhosphophylliteFluid stillInstance;
//            PhosphophylliteFluid flowingInstance;
//
//            try {
//                stillInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
//                flowingInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
//            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
//                LOGGER.error("Exception caught when instantiating instance of " + fluidClazz.getSimpleName());
//                e.printStackTrace();
//                return;
//            }
//
//            stillInstance.isSource = true;
//
//            fluids[0] = stillInstance;
//            fluids[1] = flowingInstance;
//            blockArray[0] = new LiquidBlock(stillSupplier, Block.Properties.of().noCollission().explosionResistance(100.0F).noLootTable());
//
//            for (Field declaredField : fluidClazz.getDeclaredFields()) {
//                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
//                    if (!declaredField.getType().isAssignableFrom(fluidClazz)) {
//                        LOGGER.error("Unassignable instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
//                        continue;
//                    }
//                    if (!Modifier.isStatic(declaredField.getModifiers())) {
//                        LOGGER.error("Cannot set non-static instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
//                        continue;
//                    }
//                    declaredField.setAccessible(true);
//                    try {
//                        declaredField.set(null, stillInstance);
//                    } catch (IllegalAccessException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//
//            PhosphophylliteFluid still = fluids[0];
//            PhosphophylliteFluid flowing = fluids[1];
//            if (still == null || flowing == null) {
//                return;
//            }
//
//            fluidRegistryEvent.register(baseResourceLocation, still);
//            fluidRegistryEvent.register(ResourceLocation.parse(baseRegistryName + "_flowing"), flowing);
//            if (LOGGER.isDebugEnabled()) {
//                LOGGER.debug("Fluid registered: " + baseResourceLocation);
//            }
//
//            blockRegistrationQueue.enqueueUntracked(() -> {
//                blockRegistryEvent.register(baseResourceLocation, blockArray[0]);
//                if (LOGGER.isDebugEnabled()) {
//                    LOGGER.debug("FluidBlock registered: " + baseResourceLocation);
//                }
//            });
//
//            if (annotation.registerBucket()) {
//                itemRegistrationQueue.enqueueUntracked(() -> {
//                    BucketItem bucket = new BucketItem(() -> fluids[0], new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));
//                    creativeTabItems.add(bucket);
//                    bucketArray[0] = bucket;
//                    itemRegistryEvent.register(ResourceLocation.parse(baseRegistryName + "_bucket"), bucket);
//                    if (LOGGER.isDebugEnabled()) {
//                        LOGGER.debug("Bucket registered: " + baseResourceLocation);
//                    }
//                });
//            }
//
//            fluidTypeRegistrationQueue.enqueueUntracked(() -> {
//                fluidTypeRegistryEvent.register(baseResourceLocation, fluidType);
//                if (LOGGER.isDebugEnabled()) {
//                    LOGGER.debug("FluidType registered: " + baseResourceLocation);
//                }
//            });
//        });
//    }
//
    // TODO: move this to solution similar to what is used for tile entities
    private void registerContainerAnnotation(String modNamespace, Class<?> containerClazz, final String memberName) {
        if (containerClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            if (!IS_DEV_ENVIRONMENT || containerClazz.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Registration of container at " + memberName + " in " + containerClazz.getName() + " ignored");
                }
                return;
            }
        }
        
        assert containerClazz.isAnnotationPresent(RegisterContainer.class);
        
        final RegisterContainer annotation = containerClazz.getAnnotation(RegisterContainer.class);
        
        String modid = annotation.modid();
        if (modid.equals("")) {
            modid = modNamespace;
        }
        String name = annotation.name();
        if (modid.equals("")) {
            LOGGER.error("Unable to register container without a name");
            return;
        }
        
        
        if (!AbstractContainerMenu.class.isAssignableFrom(containerClazz)) {
            LOGGER.error("Attempt to register container from class not extended from Container");
            return;
        }
        
        final String registryName = modid + ":" + name;
        
        MenuType<?>[] containerTypeArray = new MenuType[1];
        
        containerRegistrationQueue.enqueueUntracked(() -> {
            ContainerSupplier supplier = null;
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Supplier.class)) {
                    int modifiers = declaredField.getModifiers();
                    if (!Modifier.isStatic(modifiers)) {
                        LOGGER.error("Cannot access non-static container supplier " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        return;
                    }
                    if (!Modifier.isFinal(modifiers)) {
                        LOGGER.warn("Container supplier " + declaredField.getName() + " not final in" + containerClazz.getSimpleName());
                    }
                    if (!ContainerSupplier.class.isAssignableFrom(declaredField.getType())) {
                        LOGGER.error("Supplier annotation found on non-ContainerSupplier field " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (supplier != null) {
                        LOGGER.error("Duplicate suppliers for container " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        supplier = (ContainerSupplier) declaredField.get(null);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                        continue;
                    }
                    if (supplier == null) {
                        LOGGER.error("Container supplier field " + declaredField.getName() + " null in " + containerClazz.getSimpleName());
                    }
                }
            }
            if (supplier == null) {
                LOGGER.error("No supplier found for container " + containerClazz.getSimpleName());
                return;
            }
            
            ContainerSupplier finalSupplier = supplier;
            containerTypeArray[0] = IMenuTypeExtension.create((windowId, playerInventory, data) -> finalSupplier.create(windowId, data.readBlockPos(), playerInventory.player));
            
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Type.class)) {
                    if (!declaredField.getType().isAssignableFrom(MenuType.class)) {
                        LOGGER.error("Unassignable type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, containerTypeArray[0]);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            MenuType<?> type = containerTypeArray[0];
            if (type == null) {
                return;
            }
            containerRegistryEvent.register(ResourceLocation.parse(registryName), type);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Container registered: " + registryName);
            }
        });
    }
    
    private static final Field tileProducerTYPEField;
    
    static {
        try {
            tileProducerTYPEField = RegisterTile.Producer.class.getDeclaredField("TYPE");
            tileProducerTYPEField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
    
    private void registerTileAnnotation(String modNamespace, Class<?> declaringClass, final String memberName) {
        if (declaringClass.isAnnotationPresent(IgnoreRegistration.class)) {
            if (!IS_DEV_ENVIRONMENT || declaringClass.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Registration of tile at " + memberName + " in " + declaringClass.getName() + " ignored");
                }
                return;
            }
        }
        
        tileRegistrationQueue.enqueueUntracked(() -> {
            
            final Field field;
            final RegisterTile annotation;
            final RegisterTile.Producer<?> producer;
            
            try {
                field = declaringClass.getDeclaredField(memberName);
                if (!field.isAnnotationPresent(RegisterTile.class)) {
                    LOGGER.error("Schrodinger's annotation on field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                annotation = field.getAnnotation(RegisterTile.class);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (!IS_DEV_ENVIRONMENT || field.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Registration of tile at " + memberName + " in " + declaringClass.getName() + " ignored");
                        }
                        return;
                    }
                }
                field.setAccessible(true);
                var producerObject = field.get(null);
                if (producerObject == null) {
                    LOGGER.error("Null supplier for tile field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                if (producerObject.getClass() != RegisterTile.Producer.class) {
                    LOGGER.error("Attempt to register non-TileProducer BlockEntitySupplier " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                producer = (RegisterTile.Producer<?>) producerObject;
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.value();
            if (modid.equals("")) {
                LOGGER.error("Unable to register tile without a name from " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            final String registryName = modid + ":" + name;
            
            // this is safe, surely
            // should actually be, otherwise previous checks should have errored
            // i was wrong, sorta, i do need to check that this is the correct type
            // TODO: check this type, somehow
            Class<?> tileClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            
            LinkedList<Block> blocks = tileBlocks.remove(tileClass);
            
            if (blocks == null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("TileEntity has no blocks, ignoring registration: " + registryName);
                }
                return;
            }
            
            // fuck you java, its the correct size here
            @SuppressWarnings({"ConstantConditions", "ToArrayCallWithZeroLengthArrayArgument"})
            BlockEntityType<?> type = BlockEntityType.Builder.of(producer, blocks.toArray(new Block[blocks.size()])).build(null);
            
            try {
                tileProducerTYPEField.set(producer, type);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Tile entity type unable to be saved for " + memberName + " in " + declaringClass.getSimpleName());
            }
            
            tileRegistryEvent.register(ResourceLocation.parse(registryName), type);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("TileEntity registered: " + registryName);
            }
            
            // capability registration time!
            
            List<CapabilityRegistration.TileCapRegistration<BlockEntity, Object, Object>> capRegistrations = new ReferenceArrayList<>();
            
            List<Class<?>> classesToCheck = new ReferenceArrayList<>();
            classesToCheck.add(tileClass);
            while (!classesToCheck.isEmpty()) {
                var clazz = classesToCheck.remove(classesToCheck.size() - 1);
                var superClazz = clazz.getSuperclass();
                // stop at blockEntity, guaranteed to not have any registrations at that levels
                if (superClazz != null && superClazz != BlockEntity.class) {
                    classesToCheck.add(superClazz);
                }
                classesToCheck.addAll(List.of(clazz.getInterfaces()));
                
                var fields = clazz.getDeclaredFields();
                for (var capField : fields) {
                    if (capField.getType() != CapabilityRegistration.class) {
                        continue;
                    }
                    try {
                        final var modifiers = capField.getModifiers();
                        if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                            LOGGER.error("Capability registration fields must be static final. Skipping " + capField);
                            continue;
                        }
                        
                        capField.setAccessible(true);
                        var capFieldObject = capField.get(null);
                        List<?> potentialRegistrations;
                        if (capFieldObject instanceof CapabilityRegistration.TileCapRegistration.Lazy<?> lazyRegistration) {
                            potentialRegistrations = lazyRegistration.registrations();
                        } else {
                            potentialRegistrations = List.of(capFieldObject);
                        }
                        for (var potentialRegistration : potentialRegistrations) {
                            if (potentialRegistration instanceof CapabilityRegistration.TileCapRegistration<?, ?, ?> tileCapRegistration) {
                                if (!tileCapRegistration.tileClass.isAssignableFrom(tileClass)) {
                                    LOGGER.error("Skipping attempt to register tile capability for tile that doesnt extend from provided class " + tileClass + " " + tileCapRegistration.tileClass);
                                    continue;
                                }
                                //noinspection unchecked
                                capRegistrations.add((CapabilityRegistration.TileCapRegistration<BlockEntity, Object, Object>) tileCapRegistration);
                            } else {
                                LOGGER.warn("Skipping non-TileEntity capability registration from " + capField + " for " + registryName);
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            
            if (!capRegistrations.isEmpty()) {
                registerCapabilityQueue.enqueueUntracked(() -> {
                    var validBlocks = type.getValidBlocks();
                    for (var capRegistration : capRegistrations) {
                        var capability = (BlockCapability<Object, Object>) capRegistration.capability;
                        var provider = capRegistration.capabilityProvider;
                        for (var validBlock : validBlocks) {
                            if (LOGGER.isDebugEnabled()) {
                                var blockID = BuiltInRegistries.BLOCK.getKey(validBlock);
                                LOGGER.debug("Capability " + capability.name() + " registered for block " + blockID + " with tile " + registryName);
                            }
                            registerCapabilitiesEvent.registerBlock(capability, (Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Object context) -> {
                                if (blockEntity == null || blockEntity.getType() != type) {
                                    return null;
                                }
                                return provider.getCapability(blockEntity, context);
                            }, validBlock);
                        }
                    }
                    
                });
            }
        });
    }
    
    private void registerConfigAnnotation(String modNamespace, Class<?> configClazz, final String memberName) {
        try {
            Field field = configClazz.getDeclaredField(memberName);
            if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                if (!IS_DEV_ENVIRONMENT || field.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registration of config at " + memberName + " in " + configClazz.getName() + " ignored");
                    }
                    return;
                }
            }
            var configObject = field.get(null);
            var annotation = field.getAnnotation(RegisterConfig.class);
            ConfigManager.registerConfig(configObject, annotation);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Config registered: " + annotation.name() + " for " + modNamespace);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }
    
    private void onModLoadAnnotation(String modNamespace, Class<?> modLoadClazz, final String memberName) {
        try {
            Method method = modLoadClazz.getDeclaredMethod(memberName.substring(0, memberName.indexOf('(')));
            if (method.isAnnotationPresent(IgnoreRegistration.class)) {
                if (!IS_DEV_ENVIRONMENT || method.getAnnotation(IgnoreRegistration.class).ignoreInDev()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Running of @OnModLoad for " + memberName + " in " + modLoadClazz.getName() + " ignored");
                    }
                    return;
                }
            }
            
            var annotation = method.getAnnotation(OnModLoad.class);
            var required = annotation.required();
            
            if (!Modifier.isStatic(method.getModifiers())) {
                LOGGER.error("Cannot call non-static @OnModLoad method " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            if (method.getParameterCount() != 0) {
                LOGGER.error("Cannot call @OnModLoad method with parameters " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            method.setAccessible(true);
            method.invoke(null);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("@OnModLoad for " + memberName + " in " + modLoadClazz.getName() + " run");
            }
            
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        } catch (ExceptionInInitializerError | RuntimeException e) {
            LOGGER.warn(modLoadClazz.getName());
            e.printStackTrace();
        }
    }
}
