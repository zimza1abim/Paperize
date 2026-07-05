package com.anthonyla.paperize.service.livewallpaper.renderer

/**
 * GLSL shader programs for live wallpaper rendering.
 * Includes vertex shaders and fragment shaders for image and video frames.
 */
object GLShaders {

    /**
     * Simple vertex shader that passes through positions and texture coordinates.
     * Used for all rendering passes.
     */
    const val VERTEX_SHADER = """
        uniform mat4 u_mvpMatrix;
        attribute vec4 a_position;
        attribute vec2 a_texCoord;
        varying vec2 v_texCoord;

        void main() {
            gl_Position = u_mvpMatrix * a_position;
            v_texCoord = a_texCoord;
        }
    """

    /**
     * Fragment shader for live wallpaper image frames.
     */
    const val EFFECTS_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D u_texture;
        uniform float u_alpha;
        varying vec2 v_texCoord;
 
        void main() {
            vec4 color = texture2D(u_texture, v_texCoord);
            gl_FragColor = vec4(color.rgb, color.a * u_alpha);
        }
    """

    /**
     * Fragment shader for SurfaceTexture video frames.
     * Video decoder output arrives as an external OES texture, so it needs samplerExternalOES.
     */
    const val VIDEO_EFFECTS_FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES u_texture;
        uniform float u_alpha;
        varying vec2 v_texCoord;

        void main() {
            vec4 color = texture2D(u_texture, v_texCoord);
            gl_FragColor = vec4(color.rgb, color.a * u_alpha);
        }
    """

    /**
     * Solid color fragment shader for debugging and UI overlays.
     */
    const val COLOR_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 u_color;

        void main() {
            gl_FragColor = u_color;
        }
    """
}
