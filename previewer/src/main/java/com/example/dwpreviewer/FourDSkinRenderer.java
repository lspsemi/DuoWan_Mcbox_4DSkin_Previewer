package com.example.dwpreviewer;

import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.opengl.GLUtils;
import android.graphics.Bitmap;
import com.example.dwpreviewer.SkinPreviewData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL;

/* JADX INFO: loaded from: classes3.dex */
final class FourDSkinRenderer implements GLSurfaceView.Renderer {
    private static final float DUOWAN_AUTO_ROTATE_DEGREES_PER_FRAME = 0.3f;
    private static final int GL_CLAMP_TO_EDGE = 33071;
    private static final int GL_REPLACE = 7681;
    private static final int GL_TEXTURE_ENV = 8960;
    private static final int GL_TEXTURE_ENV_MODE = 8704;
    private static final float MODEL_SCALE = 0.085f;
    private final float backgroundAlpha;
    private float bodyYaw;
    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final SkinPreviewData data;
    private final boolean stillRender;
    private final float stillHalfWidth;
    private final float stillHalfHeight;
    private final float stillCameraScale;
    private final float stillFarPlane;
    private final int stillFace;
    private float headPitch;
    private float headYaw;
    private float limbSwing;
    private float modelOffsetX;
    private long lastAnimationFrameNanos;
    private List<RenderPart> renderParts;
    private long resumeAutoRotateAtMs;
    private float rootRotX;
    private float rootRotY;
    private int textureId;
    private float modelOffsetY = 10.0f;
    private float pinchScale = 1.0f;
    private boolean autoRotate = true;
    private int bodyYawDir = 1;
    private int headPitchDir = 1;
    private int headPitchSign = 1;
    private int headYawDir = 1;
    private int headYawSign = -1;
    private int limbSwingDir = 1;
    private int limbSwingSign = 1;

    FourDSkinRenderer(SkinPreviewData data, float backgroundAlpha) {
        this(data, backgroundAlpha, false);
    }

    private FourDSkinRenderer(SkinPreviewData data, float backgroundAlpha, boolean stillRender) {
        this(data, backgroundAlpha, stillRender, 1.0f);
    }

    private FourDSkinRenderer(SkinPreviewData data, float backgroundAlpha, boolean stillRender, float aspectRatio) {
        this(data, backgroundAlpha, stillRender, aspectRatio, -1);
    }

    private FourDSkinRenderer(SkinPreviewData data, float backgroundAlpha, boolean stillRender, float aspectRatio, int stillFace) {
        this.data = data;
        this.stillRender = stillRender;
        this.stillFace = stillFace;
        this.renderParts = buildRenderParts(data);
        this.backgroundAlpha = Math.max(0.0f, Math.min(1.0f, backgroundAlpha));
        float[] center = modelCenter(data);
        this.centerX = center[0];
        this.centerY = center[1];
        this.centerZ = center[2];
        float[] boundsSize = modelBoundsSize(data);
        float modelRadius = (float) (Math.sqrt(boundsSize[0] * boundsSize[0] + boundsSize[1] * boundsSize[1] + boundsSize[2] * boundsSize[2]) * 0.5f * MODEL_SCALE);
        float defaultCameraDistance = (float) Math.sqrt(4.0f * 4.0f + 3.8f * 3.8f + 4.0f * 4.0f);
        float cameraDistance = Math.max(defaultCameraDistance, modelRadius + 1.0f);
        this.stillCameraScale = cameraDistance / defaultCameraDistance;
        this.stillFarPlane = cameraDistance + modelRadius + 1.0f;
        float[] projected = stillFace < 0 ? projectedModelSize(data, center) : boundsSize;
        float fitHeight = Math.max(projected[1], projected[0] / Math.max(0.01f, aspectRatio));
        this.stillHalfHeight = stillRender ? Math.max(0.01f, fitHeight * MODEL_SCALE / (2.0f * (stillFace < 0 ? 0.96f : 0.94f))) : 2.2f;
        this.stillHalfWidth = this.stillHalfHeight * Math.max(0.01f, aspectRatio);
    }

    static Bitmap renderIsometric(SkinPreviewData data, int width, int height) {
        return renderStill(data, width, height, -1);
    }

    static Bitmap renderFace(SkinPreviewData data, int width, int height, int face) {
        return renderStill(data, width, height, face);
    }

    private static Bitmap renderStill(SkinPreviewData data, int width, int height, int face) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        OffscreenContext context = new OffscreenContext();
        try {
            context.begin(width, height);
            FourDSkinRenderer renderer = new FourDSkinRenderer(data, 0.0f, true, (float) width / Math.max(1, height), face);
            renderer.onSurfaceCreated(context.gl, null);
            renderer.onSurfaceChanged(context.gl, width, height);
            renderer.onDrawFrame(context.gl);
            context.gl.glFinish();
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            context.gl.glReadPixels(0, 0, width, height, 6408, 5121, pixels);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int red = pixels.get(i) & 255;
                    int green = pixels.get(i + 1) & 255;
                    int blue = pixels.get(i + 2) & 255;
                    int storedAlpha = pixels.get(i + 3) & 255;
                    int alpha = (int) (Math.sqrt(storedAlpha / 255.0) * 255.0 + 0.5);
                    if (alpha > 0 && alpha < 255) {
                        red = Math.min(255, red * 255 / alpha);
                        green = Math.min(255, green * 255 / alpha);
                        blue = Math.min(255, blue * 255 / alpha);
                    }
                    row[x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                }
                bitmap.setPixels(row, 0, width, 0, height - 1 - y, width, 1);
            }
            return bitmap;
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw error;
        } finally {
            context.end();
        }
    }

    void rotateBy(float dx, float dy) {
        pauseAutoRotateBriefly();
        this.rootRotY += dx;
        this.rootRotX += dy;
    }

    void zoomBy(float factor) {
        pauseAutoRotateBriefly();
        this.pinchScale = Math.max(0.15f, Math.min(4.0f, this.pinchScale * factor));
    }

    void panBy(float dx, float dy) {
        pauseAutoRotateBriefly();
        float speed = 0.035f / Math.max(DUOWAN_AUTO_ROTATE_DEGREES_PER_FRAME, this.pinchScale);
        this.modelOffsetX = clamp(this.modelOffsetX + (dx * speed), -20.0f, 20.0f);
        this.modelOffsetY = clamp(this.modelOffsetY - (dy * speed), -20.0f, 24.0f);
    }

    void resetView() {
        this.rootRotX = 0.0f;
        this.rootRotY = 0.0f;
        this.modelOffsetX = 0.0f;
        this.modelOffsetY = 10.0f;
        this.pinchScale = 1.0f;
        this.autoRotate = true;
        this.resumeAutoRotateAtMs = 0L;
    }

    void useStandardModel(boolean slimArms) {
        this.renderParts = buildRenderParts(new SkinPreviewData(this.data.texture, SkinPackageLoader.standardModel(this.data.textureHeight, slimArms), this.data.sourceName, this.data.encrypted, true, slimArms, this.data.sourceType));
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        gl.glClearColor(0.0f, 0.0f, 0.0f, this.backgroundAlpha);
        gl.glEnable(2929);
        gl.glEnable(3553);
        gl.glEnable(3042);
        gl.glBlendFunc(770, 771);
        gl.glEnable(3008);
        gl.glAlphaFunc(516, 0.1f);
        gl.glDisable(2884);
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        this.textureId = textures[0];
        gl.glBindTexture(3553, this.textureId);
        gl.glTexParameterf(3553, 10241, 9728.0f);
        gl.glTexParameterf(3553, 10240, 9728.0f);
        gl.glTexParameterf(3553, 10242, 33071.0f);
        gl.glTexParameterf(3553, 10243, 33071.0f);
        gl.glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        GLUtils.texImage2D(3553, 0, this.data.texture, 0);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(5889);
        gl.glLoadIdentity();
        float ratio = (float) width / (float) Math.max(1, height);
        if (this.stillRender) {
            gl.glOrthof(-this.stillHalfWidth, this.stillHalfWidth, -this.stillHalfHeight, this.stillHalfHeight, 0.1f, this.stillFarPlane);
        } else {
            GLU.gluPerspective(gl, 35.0f, ratio, 0.1f, 100.0f);
        }
        gl.glMatrixMode(5888);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onDrawFrame(GL10 gl) {
        if (!this.stillRender) stepDuowanPreviewAnimation();
        gl.glClear(16640);
        gl.glLoadIdentity();
        if (this.stillRender) {
            if (this.stillFace < 0) GLU.gluLookAt(gl, 4.0f * this.stillCameraScale, 3.8f * this.stillCameraScale, -4.0f * this.stillCameraScale, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
            else {
                float distance = this.stillCameraScale * 8.0f;
                switch (this.stillFace) {
                    case 0: GLU.gluLookAt(gl, 0, 0, -distance, 0, 0, 0, 0, 1, 0); break;
                    case 1: GLU.gluLookAt(gl, 0, 0, distance, 0, 0, 0, 0, 1, 0); break;
                    case 2: GLU.gluLookAt(gl, -distance, 0, 0, 0, 0, 0, 0, 1, 0); break;
                    case 3: GLU.gluLookAt(gl, distance, 0, 0, 0, 0, 0, 0, 1, 0); break;
                    case 4: GLU.gluLookAt(gl, 0, distance, 0, 0, 0, 0, 0, 0, -1); break;
                    default: GLU.gluLookAt(gl, 0, -distance, 0, 0, 0, 0, 0, 0, 1); break;
                }
            }
        }
        else GLU.gluLookAt(gl, 0.0f, 1.1f, 6.0f, 0.0f, 0.95f, 0.0f, 0.0f, 1.0f, 0.0f);
        gl.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        gl.glBindTexture(3553, this.textureId);
        gl.glPushMatrix();
        gl.glScalef(this.pinchScale * MODEL_SCALE, this.pinchScale * MODEL_SCALE, this.pinchScale * MODEL_SCALE);
        if (this.stillRender) {
            gl.glTranslatef(-this.centerX, -this.centerY, -this.centerZ);
        } else {
            gl.glTranslatef(this.modelOffsetX, this.modelOffsetY - 16.0f, 0.0f);
            gl.glTranslatef(this.centerX, this.centerY, this.centerZ);
            gl.glRotatef(this.rootRotX, 1.0f, 0.0f, 0.0f);
            gl.glRotatef(this.rootRotY, 0.0f, 1.0f, 0.0f);
            gl.glRotatef(this.bodyYaw, 0.0f, 1.0f, 0.0f);
            gl.glTranslatef(-this.centerX, -this.centerY, -this.centerZ);
        }
        for (RenderPart part : this.renderParts) {
            if (this.stillRender) drawStillPart(gl, part);
            else drawPart(gl, part);
        }
        gl.glPopMatrix();
    }

    private void drawStillPart(GL10 gl, RenderPart part) {
        gl.glPushMatrix();
        gl.glTranslatef(part.pivot[0], part.pivot[1], part.pivot[2]);
        gl.glTranslatef(-part.pivot[0], -part.pivot[1], -part.pivot[2]);
        gl.glEnableClientState(32884);
        gl.glEnableClientState(32888);
        gl.glVertexPointer(3, 5126, 0, part.vertices);
        gl.glTexCoordPointer(2, 5126, 0, part.texCoords);
        gl.glDrawArrays(4, 0, part.vertexCount);
        gl.glDisableClientState(32888);
        gl.glDisableClientState(32884);
        gl.glPopMatrix();
    }

    private void stepDuowanPreviewAnimation() {
        long nowNanos = System.nanoTime();
        float frameScale = this.lastAnimationFrameNanos == 0L
                ? 1.0f
                : Math.min(3.0f, Math.max(0.0f, (nowNanos - this.lastAnimationFrameNanos) / 16_666_667.0f));
        this.lastAnimationFrameNanos = nowNanos;
        if (!this.autoRotate && this.resumeAutoRotateAtMs > 0 && System.currentTimeMillis() >= this.resumeAutoRotateAtMs) {
            this.autoRotate = true;
            this.resumeAutoRotateAtMs = 0L;
        }
        if (this.autoRotate) {
            this.bodyYaw += DUOWAN_AUTO_ROTATE_DEGREES_PER_FRAME * frameScale;
        }
        this.headPitch += this.headPitchDir * 0.2f * frameScale;
        if (this.headPitch > 10.0f) {
            this.headPitchDir = -1;
            this.headPitch += this.headPitchDir * 0.2f * frameScale;
        } else if (this.headPitch < 0.0f) {
            this.headPitchDir = 1;
            this.headPitch += this.headPitchDir * 0.2f * frameScale;
            this.headPitchSign *= -1;
        }
        this.headYaw += this.headYawDir * 0.1f * frameScale;
        if (this.headYaw > 10.0f) {
            this.headYawDir = -1;
            this.headYaw += this.headYawDir * 0.1f * frameScale;
        } else if (this.headYaw < 0.0f) {
            this.headYawDir = 1;
            this.headYaw += this.headYawDir * 0.1f * frameScale;
            this.headYawSign *= -1;
        }
        this.limbSwing += this.limbSwingDir * 0.7f * frameScale;
        if (this.limbSwing > 30.0f) {
            this.limbSwingDir = -1;
            this.limbSwing += this.limbSwingDir * 0.7f * frameScale;
        } else if (this.limbSwing < 0.0f) {
            this.limbSwingDir = 1;
            this.limbSwing += this.limbSwingDir * 0.7f * frameScale;
            this.limbSwingSign *= -1;
        }
    }

    private void pauseAutoRotateBriefly() {
        this.autoRotate = false;
        this.resumeAutoRotateAtMs = System.currentTimeMillis() + 1800;
    }

    private void drawPart(GL10 gl, RenderPart part) {
        gl.glPushMatrix();
        gl.glTranslatef(part.pivot[0], part.pivot[1], part.pivot[2]);
        applyDuowanPartAnimation(gl, part);
        gl.glTranslatef(-part.pivot[0], -part.pivot[1], -part.pivot[2]);
        gl.glEnableClientState(32884);
        gl.glEnableClientState(32888);
        gl.glVertexPointer(3, 5126, 0, part.vertices);
        gl.glTexCoordPointer(2, 5126, 0, part.texCoords);
        gl.glDrawArrays(4, 0, part.vertexCount);
        gl.glDisableClientState(32888);
        gl.glDisableClientState(32884);
        gl.glPopMatrix();
    }

    private void applyDuowanPartAnimation(GL10 gl, RenderPart part) {
        String name = part.name;
        String parent = part.parent;
        if (matches(name, parent, "leftArm") || matches(name, parent, "rightLeg")) {
            gl.glRotatef(this.limbSwing * this.limbSwingSign, 1.0f, 0.0f, 0.0f);
            return;
        }
        if (matches(name, parent, "rightArm") || matches(name, parent, "leftLeg")) {
            gl.glRotatef((-this.limbSwing) * this.limbSwingSign, 1.0f, 0.0f, 0.0f);
        } else if (matches(name, parent, "head")) {
            gl.glRotatef(this.headPitch * this.headPitchSign, 1.0f, 0.0f, 0.0f);
            gl.glRotatef(this.headYaw * this.headYawSign, 0.0f, 1.0f, 0.0f);
        }
    }

    private static boolean matches(String name, String parent, String token) {
        return token.equals(name) || token.equals(parent) || name.contains(token) || parent.contains(token);
    }

    private static List<RenderPart> buildRenderParts(SkinPreviewData data) {
        ArrayList<RenderPart> parts = new ArrayList<>();
        for (SkinPreviewData.Part part : data.parts) {
            parts.add(buildRenderPart(data, part));
        }
        return parts;
    }

    private static float[] modelCenter(SkinPreviewData data) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -3.4028235E38f;
        float maxY = -3.4028235E38f;
        float maxZ = -3.4028235E38f;
        boolean hasCube = false;
        Iterator<SkinPreviewData.Part> it = data.parts.iterator();
        while (true) {
            char c = 1;
            char c2 = 0;
            if (!it.hasNext()) {
                break;
            }
            SkinPreviewData.Part part = it.next();
            for (SkinPreviewData.Cube cube : part.cubes) {
                float inflate = cube.inflate;
                float x1 = cube.origin[c2] - inflate;
                float y1 = cube.origin[c] - inflate;
                float z1 = cube.origin[2] - inflate;
                float x2 = cube.origin[0] + cube.size[0] + inflate;
                Iterator<SkinPreviewData.Part> it2 = it;
                float y2 = cube.origin[1] + cube.size[1] + inflate;
                float z2 = cube.origin[2] + cube.size[2] + inflate;
                minX = Math.min(minX, x1);
                minY = Math.min(minY, y1);
                minZ = Math.min(minZ, z1);
                maxX = Math.max(maxX, x2);
                maxY = Math.max(maxY, y2);
                maxZ = Math.max(maxZ, z2);
                hasCube = true;
                it = it2;
                part = part;
                c = 1;
                c2 = 0;
            }
        }
        if (hasCube) {
            return new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f};
        }
        return new float[]{0.0f, 16.0f, 0.0f};
    }

    private static float[] projectedModelSize(SkinPreviewData data, float[] center) {
        double viewLength = Math.sqrt(4.0 * 4.0 + 3.8 * 3.8 + 4.0 * 4.0);
        float fx = (float) (-4.0 / viewLength);
        float fy = (float) (-3.8 / viewLength);
        float fz = (float) (4.0 / viewLength);
        float rx = (float) (-Math.sqrt(0.5));
        float rz = rx;
        float ux = -rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy;
        float minScreenX = Float.MAX_VALUE;
        float maxScreenX = -Float.MAX_VALUE;
        float minScreenY = Float.MAX_VALUE;
        float maxScreenY = -Float.MAX_VALUE;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        boolean hasCube = false;
        for (SkinPreviewData.Part part : data.parts) {
            for (SkinPreviewData.Cube cube : part.cubes) {
                float inflate = cube.inflate;
                minX = Math.min(minX, cube.origin[0] - inflate);
                minY = Math.min(minY, cube.origin[1] - inflate);
                minZ = Math.min(minZ, cube.origin[2] - inflate);
                maxX = Math.max(maxX, cube.origin[0] + cube.size[0] + inflate);
                maxY = Math.max(maxY, cube.origin[1] + cube.size[1] + inflate);
                maxZ = Math.max(maxZ, cube.origin[2] + cube.size[2] + inflate);
                hasCube = true;
            }
        }
        if (!hasCube) return new float[]{32.0f, 32.0f};
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    float px = (x == 0 ? minX : maxX) - center[0];
                    float py = (y == 0 ? minY : maxY) - center[1];
                    float pz = (z == 0 ? minZ : maxZ) - center[2];
                    float screenX = px * rx + pz * rz;
                    float screenY = px * ux + py * uy + pz * uz;
                    minScreenX = Math.min(minScreenX, screenX);
                    maxScreenX = Math.max(maxScreenX, screenX);
                    minScreenY = Math.min(minScreenY, screenY);
                    maxScreenY = Math.max(maxScreenY, screenY);
                }
            }
        }
        return new float[]{Math.max(1.0f, maxScreenX - minScreenX), Math.max(1.0f, maxScreenY - minScreenY)};
    }

    private static float[] modelBoundsSize(SkinPreviewData data) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (SkinPreviewData.Part part : data.parts) {
            for (SkinPreviewData.Cube cube : part.cubes) {
                float inflate = cube.inflate;
                minX = Math.min(minX, cube.origin[0] - inflate);
                minY = Math.min(minY, cube.origin[1] - inflate);
                minZ = Math.min(minZ, cube.origin[2] - inflate);
                maxX = Math.max(maxX, cube.origin[0] + cube.size[0] + inflate);
                maxY = Math.max(maxY, cube.origin[1] + cube.size[1] + inflate);
                maxZ = Math.max(maxZ, cube.origin[2] + cube.size[2] + inflate);
            }
        }
        if (minX == Float.MAX_VALUE) return new float[]{32.0f, 32.0f, 8.0f};
        return new float[]{maxX - minX, maxY - minY, maxZ - minZ};
    }

    private static RenderPart buildRenderPart(SkinPreviewData data, SkinPreviewData.Part part) {
        int faceCount = part.cubes.size() * 6;
        float[] vertices = new float[faceCount * 6 * 3];
        float[] texCoords = new float[faceCount * 6 * 2];
        int[] vertexOffset = {0};
        int[] texOffset = {0};
        for (SkinPreviewData.Cube cube : part.cubes) {
            appendCube(data, cube, vertices, texCoords, vertexOffset, texOffset);
        }
        return new RenderPart(part.name, part.parent, part.pivot, vertices, texCoords, vertexOffset[0] / 3);
    }

    private static void appendCube(SkinPreviewData data, SkinPreviewData.Cube cube, float[] vertices, float[] texCoords, int[] vertexOffset, int[] texOffset) {
        float inflate = cube.inflate;
        float x1 = cube.origin[0] - inflate;
        float y1 = cube.origin[1] - inflate;
        float z1 = cube.origin[2] - inflate;
        float x2 = cube.origin[0] + cube.size[0] + inflate;
        float y2 = cube.origin[1] + cube.size[1] + inflate;
        float z2 = cube.origin[2] + cube.size[2] + inflate;
        float[][] uv = boxUv(data, cube.uv[0], cube.uv[1], cube.size, cube.mirror);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x2, y2, z1, x1, y2, z1, x1, y1, z1, x2, y1, z1}, uv[0]);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x2, y2, z2, x2, y2, z1, x2, y1, z1, x2, y1, z2}, uv[1]);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x1, y2, z2, x2, y2, z2, x2, y1, z2, x1, y1, z2}, uv[2]);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x1, y2, z1, x1, y2, z2, x1, y1, z2, x1, y1, z1}, uv[3]);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2}, uv[4]);
        appendQuad(vertices, texCoords, vertexOffset, texOffset, new float[]{x1, y1, z2, x2, y1, z2, x2, y1, z1, x1, y1, z1}, uv[5]);
    }

    private static float[][] boxUv(SkinPreviewData data, int u, int v, float[] size, boolean mirror) {
        float w = size[0];
        float h = size[1];
        float d = size[2];
        float tw = data.textureWidth;
        float th = data.textureHeight;
        return new float[][]{rect((u + d) / tw, (v + d) / th, ((u + d) + w) / tw, ((v + d) + h) / th, mirror), rect(((u + d) + w) / tw, (v + d) / th, (((u + d) + w) + d) / tw, ((v + d) + h) / th, mirror), rect((((u + d) + w) + d) / tw, (v + d) / th, ((((u + d) + w) + d) + w) / tw, ((v + d) + h) / th, mirror), rect(u / tw, (v + d) / th, (u + d) / tw, ((v + d) + h) / th, mirror), rect((u + d) / tw, v / th, ((u + d) + w) / tw, (v + d) / th, mirror), rect(((u + d) + w) / tw, v / th, (((u + d) + w) + w) / tw, (v + d) / th, mirror)};
    }

    private static float[] rect(float u1, float v1, float u2, float v2, boolean mirror) {
        if (mirror) {
            float oldU1 = u1;
            u1 = u2;
            u2 = oldU1;
        }
        return new float[]{u1, v1, u2, v1, u2, v2, u1, v2};
    }

    private static void appendQuad(float[] vertices, float[] texCoords, int[] vertexOffset, int[] texOffset, float[] quadVertices, float[] quadTexCoords) {
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int index : order) {
            int v = index * 3;
            int i = vertexOffset[0];
            vertexOffset[0] = i + 1;
            vertices[i] = quadVertices[v];
            int i2 = vertexOffset[0];
            vertexOffset[0] = i2 + 1;
            vertices[i2] = quadVertices[v + 1];
            int i3 = vertexOffset[0];
            vertexOffset[0] = i3 + 1;
            vertices[i3] = quadVertices[v + 2];
            int t = index * 2;
            int i4 = texOffset[0];
            texOffset[0] = i4 + 1;
            texCoords[i4] = quadTexCoords[t];
            int i5 = texOffset[0];
            texOffset[0] = i5 + 1;
            texCoords[i5] = quadTexCoords[t + 1];
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static FloatBuffer direct(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4);
        buffer.order(ByteOrder.nativeOrder());
        FloatBuffer floats = buffer.asFloatBuffer();
        floats.put(values);
        floats.position(0);
        return floats;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class RenderPart {
        final String name;
        final String parent;
        final float[] pivot;
        final FloatBuffer texCoords;
        final int vertexCount;
        final FloatBuffer vertices;

        RenderPart(String name, String parent, float[] pivot, float[] vertices, float[] texCoords, int vertexCount) {
            this.name = name == null ? "" : name;
            this.parent = parent != null ? parent : "";
            this.pivot = pivot;
            this.vertices = FourDSkinRenderer.direct(vertices);
            this.texCoords = FourDSkinRenderer.direct(texCoords);
            this.vertexCount = vertexCount;
        }
    }

    private static final class OffscreenContext {
        final EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display;
        EGLContext context;
        EGLSurface surface;
        GL10 gl;

        void begin(int width, int height) {
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!egl.eglInitialize(display, version)) throw new IllegalStateException("EGL initialization failed");
            int[] attributes = {12324, 8, 12323, 8, 12322, 8, 12321, 8, 12325, 16, 12339, 1, 12352, 1, 12344};
            javax.microedition.khronos.egl.EGLConfig[] configs = new javax.microedition.khronos.egl.EGLConfig[1];
            int[] count = new int[1];
            if (!egl.eglChooseConfig(display, attributes, configs, 1, count) || count[0] == 0) throw new IllegalStateException("No alpha EGL config");
            int[] pbuffer = {12375, width, 12374, height, 12344};
            surface = egl.eglCreatePbufferSurface(display, configs[0], pbuffer);
            int[] contextAttributes = {12440, 1, 12344};
            context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, contextAttributes);
            if (surface == null || context == null || surface == EGL10.EGL_NO_SURFACE || context == EGL10.EGL_NO_CONTEXT
                    || !egl.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("EGL pbuffer creation failed");
            gl = (GL10) context.getGL();
        }

        void end() {
            if (display != null && display != EGL10.EGL_NO_DISPLAY) {
                egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                if (surface != null) egl.eglDestroySurface(display, surface);
                if (context != null) egl.eglDestroyContext(display, context);
                egl.eglTerminate(display);
            }
        }
    }
}
