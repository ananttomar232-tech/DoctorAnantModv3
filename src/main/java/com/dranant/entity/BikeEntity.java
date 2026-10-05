package com.dranant.entity;

import com.dranant.registry.ModItems;
import com.dranant.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Dr. Anant's motorbike: 2 seats (driver + sidekick), W/S throttle & brake, A/D steering with lean, climbs 1-block steps.
 * Right-click to ride, punch to pick it back up.
 */
public class BikeEntity extends Entity {
    private static final double MAX_SPEED = 0.95D;
    private double speed;
    private float turnInput;
    // client visuals
    public float wheelRot;
    public float wheelRotO;
    public float lean;
    // remote interpolation
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;

    public BikeEntity(EntityType<? extends BikeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    public float maxUpStep() {
        return 1.0F;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player p ? p : null;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (!level().isClientSide) {
            if (player.startRiding(this)) {
                level().playSound(null, blockPosition(), ModSounds.BIKE_HORN.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) return true;
        if (source.getEntity() instanceof Player player) {
            if (!player.getAbilities().instabuild) spawnAtLocation(new ItemStack(ModItems.DR_ANANT_BIKE.get()));
            ejectPassengers();
            discard();
            return true;
        }
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ModItems.DR_ANANT_BIKE.get());
    }

    // ------------------------------------------------------------------ driving
    @Override
    public void tick() {
        super.tick();
        wheelRotO = wheelRot;
        if (isControlledByLocalInstance()) {
            lerpSteps = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
            LivingEntity driver = getControllingPassenger();
            float forward = driver != null ? driver.zza : 0.0F;
            turnInput = driver != null ? driver.xxa : 0.0F;
            if (forward > 0) speed = Math.min(MAX_SPEED, speed + 0.045D);
            else if (forward < 0) speed = Math.max(-0.3D, speed - 0.06D);
            else speed *= 0.93D;
            if (Math.abs(speed) < 0.003D) speed = 0.0D;
            float turnRate = (float) (4.5D * Math.min(1.0D, Math.abs(speed) * 2.5D + 0.25D));
            setYRot(getYRot() - turnInput * turnRate * (speed < 0 ? -1 : 1));
            Vec3 dir = Vec3.directionFromRotation(0.0F, getYRot());
            double dy = getDeltaMovement().y;
            dy = onGround() ? -0.04D : dy - 0.08D;
            if (horizontalCollision && onGround() && Math.abs(speed) > 0.05D) dy = 0.42D;
            setDeltaMovement(dir.x * speed, dy, dir.z * speed);
            move(MoverType.SELF, getDeltaMovement());
            if (driver == null) speed *= 0.8D;
        } else {
            tickLerp();
        }
        if (level().isClientSide) {
            double moved = Math.sqrt(Math.pow(getX() - xo, 2) + Math.pow(getZ() - zo, 2));
            wheelRot += (float) (moved * 2.2D);
            lean += ((-turnInput * 0.35F * (float) Math.min(1.0D, Math.abs(speed) * 2)) - lean) * 0.2F;
            if (moved > 0.05D && random.nextInt(3) == 0) {
                Vec3 back = Vec3.directionFromRotation(0.0F, getYRot()).scale(-1.1D);
                level().addParticle(ParticleTypes.SMOKE, getX() + back.x, getY() + 0.35D, getZ() + back.z, 0, 0.02, 0);
            }
        } else if (getControllingPassenger() != null && Math.abs(speed) > 0.1D && tickCount % 24 == 0) {
            level().playSound(null, blockPosition(), ModSounds.BIKE_ENGINE.get(), SoundSource.NEUTRAL, 0.5F, 0.8F + (float) Math.abs(speed));
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = 10;
    }

    private void tickLerp() {
        if (lerpSteps <= 0) return;
        double nx = getX() + (lerpX - getX()) / lerpSteps;
        double ny = getY() + (lerpY - getY()) / lerpSteps;
        double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
        setYRot(getYRot() + Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps);
        lerpSteps--;
        setPos(nx, ny, nz);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        if (!hasPassenger(passenger)) return;
        int index = getPassengers().indexOf(passenger);
        double offset = index == 0 ? 0.1D : -0.55D;
        Vec3 dir = Vec3.directionFromRotation(0.0F, getYRot());
        callback.accept(passenger, getX() + dir.x * offset, getY() + 0.3D, getZ() + dir.z * offset);
        if (passenger instanceof LivingEntity living && !(passenger instanceof Player)) {
            living.setYBodyRot(getYRot());
        }
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }
}
