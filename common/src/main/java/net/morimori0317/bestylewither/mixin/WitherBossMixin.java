package net.morimori0317.bestylewither.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.HitResult;
import net.morimori0317.bestylewither.entity.BEWitherBoss;
import net.morimori0317.bestylewither.entity.goal.WitherChargeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(WitherBoss.class)
public abstract class WitherBossMixin extends Monster implements BEWitherBoss {
    private int witherDeathTime;
    private int witherDeathTimeOld;
    private int chargeTickCoolDown;
    private int clientChargeTick;
    private int clientChargeTickOld;

    @Shadow
    public abstract int getInvulnerableTicks();

    @Shadow
    @Final
    private float[] yRotHeads;

    @Shadow
    public abstract boolean isPowered();

    @Shadow
    private int destroyBlocksTick;
    @Shadow @Final private ServerBossEvent bossEvent;
    private static final EntityDataAccessor<Boolean> DATA_ID_FORCED_POWER = SynchedEntityData.defineId(WitherBoss.class, EntityDataSerializers.BOOLEAN);

    protected WitherBossMixin(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
    }

    @Redirect(method = "performRangedAttack(ILnet/minecraft/world/entity/LivingEntity;)V", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextFloat()F", remap = false, ordinal = 0))
    private float injected(Random instance) {
        return 0f;
    }

    @Inject(method = "isPowered", at = @At("RETURN"), cancellable = true)
    private void isPowered(CallbackInfoReturnable<Boolean> cir) {
        if (isForcedPowered() || witherDeathTime > 0)
            cir.setReturnValue(isForcedPowered());
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void registerGoals(CallbackInfo ci) {
        this.goalSelector.addGoal(1, new WitherChargeAttackGoal((WitherBoss) (Object) this));
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void defineSynchedData(CallbackInfo ci) {
        this.entityData.define(DATA_ID_FORCED_POWER, false);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void addAdditionalSaveData(CompoundTag compoundTag, CallbackInfo ci) {
        compoundTag.putBoolean("FPower", isForcedPowered());
        compoundTag.putShort("WitherDeathTime", (short) this.witherDeathTime);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readAdditionalSaveData(CompoundTag compoundTag, CallbackInfo ci) {
        setForcedPowered(compoundTag.getBoolean("FPower"));
        this.witherDeathTime = compoundTag.getShort("WitherDeathTime");
    }

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void aiStepPre(CallbackInfo ci) {
        if (getWitherDeathTime() > 0)
            ci.cancel();
    }

    @Override
    public void tick() {
        super.tick();
        this.witherDeathTimeOld = witherDeathTime;

        if (getInvulnerableTicks() <= 0 && isPowered() && !isForcedPowered())
            setDeltaMovement(getDeltaMovement().add(0, -0.7f, 0));
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void aiStepPost(CallbackInfo ci) {
        int it = getInvulnerableTicks();
        if (it > 0) {
            float par = 1f - ((float) it / 220f);
            float angle = (60f * par) + 5f;
            setYBodyRot(yBodyRot + angle);
            setYHeadRot(getYHeadRot() + angle);
            for (int i = 0; i < yRotHeads.length; i++) {
                yRotHeads[i] = yRotHeads[i] + angle;
            }
        }

        setChargeCoolDown(Math.max(0, getChargeCoolDown() - 1));
        if (level.isClientSide()) {
            clientChargeTickOld = clientChargeTick;
            clientChargeTick = Math.max(0, clientChargeTick - 1);
        }
    }

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void customServerAiStep(CallbackInfo ci) {
        if (getInvulnerableTicks() <= 0 && isPowered() && !isForcedPowered()) {
            var clip = level.clip(new ClipContext(position(), position().add(0, -30, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

            boolean flg = true;
            boolean exFlg = false;

            if (clip.getType() != HitResult.Type.MISS) {
                flg = Math.sqrt(clip.distanceTo(this)) <= 1 || isOnGround();
                exFlg = true;
            }

            if (flg) {
                setForcedPowered(true);
                var interaction = this.level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.NONE;
                this.level.explode(this, this.getX(), this.getEyeY(), this.getZ(), 5.0F, false, interaction);
                if (!this.isSilent())
                    this.level.globalLevelEvent(LevelEvent.SOUND_WITHER_BLOCK_BREAK, this.blockPosition(), 0);

                if (exFlg && (this.level.getDifficulty() == Difficulty.NORMAL || this.level.getDifficulty() == Difficulty.HARD)) {
                    int wc = 3;
                    if (random.nextInt(8) == 0)
                        wc = 4;

                    for (int i = 0; i < wc; i++) {
                        WitherSkeleton ws = new WitherSkeleton(EntityType.WITHER_SKELETON, level);
                        ws.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), 0.0F);
                        ws.finalizeSpawn((ServerLevelAccessor) level, level.getCurrentDifficultyAt(blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
                        level.addFreshEntity(ws);
                    }
                }
            }
        }
    }

    private void setForcedPowered(boolean powered) {
        this.entityData.set(DATA_ID_FORCED_POWER, powered);
    }

    private boolean isForcedPowered() {
        return this.entityData.get(DATA_ID_FORCED_POWER);
    }

    @Override
    protected void tickDeath() {
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());

        ++this.witherDeathTime;
        this.witherDeathTime = Math.min(this.witherDeathTime, 20 * 10);

        if (!this.level.isClientSide()) {
            if (this.witherDeathTime % 4 == 0)
                setForcedPowered(random.nextInt((int) Math.max(5 - ((float) this.witherDeathTime / (20f * 10f) * 5f), 1)) == 0);

            if (this.witherDeathTime == 20 * 10) {

                var interaction = this.level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.NONE;
                this.level.explode(this, this.getX(), this.getEyeY(), this.getZ(), 8f, false, interaction);
                if (!this.isSilent())
                    this.level.globalLevelEvent(LevelEvent.SOUND_WITHER_BLOCK_BREAK, this.blockPosition(), 0);

                this.level.broadcastEntityEvent(this, (byte) 60);
                this.remove(RemovalReason.KILLED);
            }
        }
    }

    @Override
    public int getWitherDeathTime() {
        return witherDeathTime;
    }

    @Override
    public float getWitherDeathTime(float delta) {
        return Mth.lerp(delta, (float) this.witherDeathTimeOld, (float) this.witherDeathTime) / (float) (30 - 2);
    }

    @Override
    public int getDestroyBlocksTick() {
        return this.destroyBlocksTick;
    }

    @Override
    public void setDestroyBlocksTick(int tick) {
        this.destroyBlocksTick = tick;
    }

    @Override
    public int getChargeCoolDown() {
        return chargeTickCoolDown;
    }

    @Override
    public void setChargeCoolDown(int tick) {
        this.chargeTickCoolDown = tick;
    }

    @Override
    public void setClientCharge(int charge) {
        this.clientChargeTick = charge;
    }

    @Override
    public int getClientCharge() {
        return this.clientChargeTick;
    }

    @Override
    public float getClientCharge(float delta) {
        return Mth.lerp(delta, clientChargeTickOld, clientChargeTick);
    }
}
