package me.cortex.zenith.client.core.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.zenith.client.core.gl.GlBuffer;
import me.cortex.zenith.client.core.gl.shader.Shader;
import me.cortex.zenith.client.core.gl.shader.ShaderType;
import me.cortex.zenith.client.core.rendering.util.UploadStream;
import me.cortex.zenith.client.mixin.joml.AccessFrustumIntersection;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.ARBMultiDrawIndirect.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL42.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45C.glClearNamedBufferData;

public class Gl46FarWorldRenderer extends AbstractFarWorldRenderer {
    private final Shader commandGen = Shader.make()
            .add(ShaderType.COMPUTE, "zenith:lod/gl46/cmdgen.comp")
            .compile();

    private final Shader lodShader = Shader.make()
            .add(ShaderType.VERTEX, "zenith:lod/gl46/quads.vert")
            .add(ShaderType.FRAGMENT, "zenith:lod/gl46/quads.frag")
            .compile();


    //TODO: Note the cull shader needs a different element array since its rastering cubes not quads
    private final Shader cullShader = Shader.make()
            .add(ShaderType.VERTEX, "zenith:lod/gl46/cull/raster.vert")
            .add(ShaderType.FRAGMENT, "zenith:lod/gl46/cull/raster.frag")
            .compile();

    private final GlBuffer glCommandBuffer;
    private final GlBuffer glVisibilityBuffer;

    public Gl46FarWorldRenderer(int geometryBuffer, int maxSections) {
        super(geometryBuffer, maxSections);
        this.glCommandBuffer = new GlBuffer(maxSections*5L*4);
        this.glVisibilityBuffer = new GlBuffer(maxSections*4L);
        glClearNamedBufferData(this.glCommandBuffer.id, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[1]);
        glClearNamedBufferData(this.glVisibilityBuffer.id, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[1]);
        setupVao();
    }

    @Override
    protected void setupVao() {
        glBindVertexArray(this.vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.glCommandBuffer.id);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometry.geometryId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.glCommandBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometry.metaId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, this.glVisibilityBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, this.models.getBufferId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, this.lightDataBuffer.id);//Lighting LUT
        glBindVertexArray(0);
    }

    public void renderFarAwayOpaque(MatrixStack stack, double cx, double cy, double cz) {
        if (this.geometry.getSectionCount() == 0) {
            return;
        }


        RenderLayer.getCutoutMipped().startDrawing();
        int oldActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int oldBoundTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        //RenderSystem.enableBlend();
        //RenderSystem.defaultBlendFunc();

        this.updateUniformBuffer(stack, cx, cy, cz);

        UploadStream.INSTANCE.commit();

        glBindVertexArray(this.vao);


        //Bind the texture atlas
        glBindSampler(0, this.models.getSamplerId());
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, this.models.getTextureId());


        this.commandGen.bind();
        glDispatchCompute((this.geometry.getSectionCount() + 127) / 128, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT);

        this.lodShader.bind();
        glDisable(GL_CULL_FACE);
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, this.geometry.getSectionCount(), 0);
        glEnable(GL_CULL_FACE);
        //ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(

        glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);

        this.cullShader.bind();
        glColorMask(false, false, false, false);
        glDepthMask(false);

        //glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3, GL_UNSIGNED_BYTE, (1 << 16) * 6 * 2, this.geometry.getSectionCount());
        //glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

        glDepthMask(true);
        glColorMask(true, true, true, true);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);


        //TODO: need to do temporal rasterization here

        glBindVertexArray(0);
        glBindSampler(0, 0);
        GL11C.glBindTexture(GL_TEXTURE_2D, oldBoundTexture);
        glActiveTexture(oldActiveTexture);
        RenderLayer.getCutoutMipped().endDrawing();
    }

    //FIXME: dont do something like this as it breaks multiviewport mods
    private int frameId = 0;
    private void updateUniformBuffer(MatrixStack stack, double cx, double cy, double cz) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, this.uniformBuffer.size());
        var mat = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(stack.peek().getPositionMatrix());
        var innerTranslation = new Vector3f((float) (cx-(this.sx<<5)), (float) (cy-(this.sy<<5)), (float) (cz-(this.sz<<5)));
        mat.translate(-innerTranslation.x, -innerTranslation.y, -innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;
        MemoryUtil.memPutInt(ptr, this.sx); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.sy); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.sz); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.geometry.getSectionCount()); ptr += 4;
        var planes = ((AccessFrustumIntersection)this.frustum).getPlanes();
        for (var plane : planes) {
            plane.getToAddress(ptr); ptr += 4*4;
        }
        innerTranslation.getToAddress(ptr); ptr += 4*3;
        MemoryUtil.memPutInt(ptr, this.frameId++); ptr += 4;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.commandGen.free();
        this.lodShader.free();
        this.cullShader.free();
        this.glCommandBuffer.free();
        this.glVisibilityBuffer.free();
    }

    @Override
    public void addDebugData(List<String> debug) {
        debug.add("Geometry buffer usage: " + ((float)Math.round((this.geometry.getGeometryBufferUsage()*100000))/1000) + "%");
        debug.add("Render Sections: " + this.geometry.getSectionCount());
    }
}
