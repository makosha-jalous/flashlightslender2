package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side renderer of the flashlight in first person.
 * - OFF: nothing of the hand or the flashlight is drawn.
 * - ON : a fake arm comes from the RIGHT edge of the screen, dips into the flashlight;
 *        the flashlight itself is drawn at the right side, slightly tilted.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightRenderer {

    private static PlayerModel<AbstractClientPlayer> armModel;

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(SlenderFlashlightMod.FLASHLIGHT.get())) return;

        // We always take over: this is how OFF hides the item AND the arm.
        event.setCanceled(true);

        if (!FlashlightItem.isOn(stack)) return;

        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof AbstractClientPlayer player)) return;

        boolean rightHand = event.getHand() == InteractionHand.MAIN_HAND;
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buf = event.getMultiBufferSource();
        int light = event.getPackedLight();

        // ---------------- 1) Draw the fake arm (comes from the RIGHT edge). ----------------
        renderArm(ps, buf, light, player, rightHand, event.getEquipProcess(), event.getSwingProgress(), event.getPartialTick());

        // ---------------- 2) Draw the flashlight in the hand. ----------------
        ps.pushPose();

        // Position: right side of the screen, slightly below the middle, tilted towards the arm.
        ps.translate(rightHand ? 0.62 : -0.62, -0.44, -0.68);
        ps.mulPose(Axis.YP.rotationDegrees(rightHand ? -32.0F : 32.0F));
        ps.mulPose(Axis.XP.rotationDegrees(-14.0F));
        ps.mulPose(Axis.ZP.rotationDegrees(rightHand ? 8.0F : -8.0F));
        ps.scale(1.0F, 1.0F, 1.0F);

        mc.getItemRenderer().renderStatic(
                stack,
                rightHand ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                light,
                OverlayTexture.NO_OVERLAY,
                ps,
                buf,
                player,
                0);

        ps.popPose();
    }

    /**
     * A trimmed PlayerModel arm, rotated so it comes from the right of the screen and dips forward
     * into the flashlight. Only the arm we need is drawn (head / body / legs are skipped).
     */
    private static void renderArm(PoseStack ps,
                                  MultiBufferSource buf,
                                  int light,
                                  AbstractClientPlayer player,
                                  boolean rightHand,
                                  float equipProcess,
                                  float swingProgress,
                                  float partialTick) {
        if (armModel == null) {
            armModel = new PlayerModel<>(
                    Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }

        // Configure the model to show only the desired arm.
        armModel.attackTime = 0.0F;
        armModel.crouching = false;
        armModel.riding = false;
        armModel.young = false;
        armModel.leftArm.visible = false;
        armModel.rightArm.visible = false;
        armModel.leftLeg.visible = false;
        armModel.rightLeg.visible = false;
        armModel.hat.visible = false;
        armModel.head.visible = false;
        armModel.body.visible = false;
        armModel.jacket.visible = false;
        armModel.leftSleeve.visible = false;
        armModel.rightSleeve.visible = false;
        armModel.leftPants.visible = false;
        armModel.rightPants.visible = false;

        // Right or left arm in front of the camera.
        if (rightHand) {
            armModel.rightArm.visible = true;
            armModel.rightArm.x = 0.0F;
            armModel.rightArm.y = 1.2F;
            armModel.rightArm.z = 0.0F;
            // Come in from the right side, pivoted so the palm points at the flashlight.
            armModel.rightArm.xRot = (float) Math.toRadians(-75.0F - equipProcess * 20.0F);
            armModel.rightArm.yRot = (float) Math.toRadians(-48.0F);
            armModel.rightArm.zRot = (float) Math.toRadians(-12.0F + swingProgress * 25.0F);
        } else {
            armModel.leftArm.visible = true;
            armModel.leftArm.x = 0.0F;
            armModel.leftArm.y = 1.2F;
            armModel.leftArm.z = 0.0F;
            armModel.leftArm.xRot = (float) Math.toRadians(-75.0F - equipProcess * 20.0F);
            armModel.leftArm.yRot = (float) Math.toRadians(48.0F);
            armModel.leftArm.zRot = (float) Math.toRadians(12.0F - swingProgress * 25.0F);
        }

        VertexConsumer vc = buf.getBuffer(RenderType.entitySolid(player.getSkinTextureLocation()));
        armModel.renderToBuffer(ps, vc, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}