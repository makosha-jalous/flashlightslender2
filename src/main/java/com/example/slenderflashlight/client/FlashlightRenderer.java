package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemTransforms;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side renderer of the flashlight in first person (1.19.2).
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

        // We always take over: OFF hides the item AND the arm, ON draws our own rig.
        event.setCanceled(true);

        if (!FlashlightItem.isOn(stack)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        AbstractClientPlayer player = (AbstractClientPlayer) mc.player;

        boolean rightHand = event.getHand() == InteractionHand.MAIN_HAND;
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buf = event.getMultiBufferSource();
        int light = event.getPackedLight();

        // ---------------- 1) Draw the fake arm (comes from the RIGHT edge). ----------------
        renderArm(ps, buf, light, player, rightHand);

        // ---------------- 2) Draw the flashlight in the hand. ----------------
        ps.pushPose();

        ps.translate(rightHand ? 0.62D : -0.62D, -0.44D, -0.68D);
        ps.mulPose(Vector3f.YP.rotationDegrees(rightHand ? -32.0F : 32.0F));
        ps.mulPose(Vector3f.XP.rotationDegrees(-14.0F));
        ps.mulPose(Vector3f.ZP.rotationDegrees(rightHand ? 8.0F : -8.0F));

        // 1.19.2 signature: renderStatic(ItemStack, TransformType, light, overlay, pose, buffer, seed)
        mc.getItemRenderer().renderStatic(
                stack,
                rightHand ? ItemTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
                          : ItemTransforms.TransformType.FIRST_PERSON_LEFT_HAND,
                light,
                OverlayTexture.NO_OVERLAY,
                ps,
                buf,
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
                                  boolean rightHand) {
        if (armModel == null) {
            armModel = new PlayerModel<>(
                    Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }

        // Only the desired arm is visible.
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

        if (rightHand) {
            armModel.rightArm.visible = true;
            armModel.rightArm.x = 0.0F;
            armModel.rightArm.y = 1.2F;
            armModel.rightArm.z = 0.0F;
            armModel.rightArm.xRot = (float) Math.toRadians(-75.0F);
            armModel.rightArm.yRot = (float) Math.toRadians(-48.0F);
            armModel.rightArm.zRot = (float) Math.toRadians(-12.0F);
        } else {
            armModel.leftArm.visible = true;
            armModel.leftArm.x = 0.0F;
            armModel.leftArm.y = 1.2F;
            armModel.leftArm.z = 0.0F;
            armModel.leftArm.xRot = (float) Math.toRadians(-75.0F);
            armModel.leftArm.yRot = (float) Math.toRadians(48.0F);
            armModel.leftArm.zRot = (float) Math.toRadians(12.0F);
        }

        VertexConsumer vc = buf.getBuffer(RenderType.entitySolid(player.getSkinTextureLocation()));
        armModel.renderToBuffer(ps, vc, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}