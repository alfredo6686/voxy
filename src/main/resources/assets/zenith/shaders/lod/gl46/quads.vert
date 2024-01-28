#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#import <zenith:lod/gl46/quad_format.glsl>
#import <zenith:lod/gl46/bindings.glsl>
#import <zenith:lod/gl46/block_model.glsl>
#line 8

layout(location = 0) out vec2 uv;
layout(location = 1) out flat vec2 baseUV;
layout(location = 2) out flat vec4 colourTinting;
layout(location = 3) out flat int discardAlpha;

uint extractLodLevel() {
    return uint(gl_BaseInstance)>>29;
}

//Note the last 2 bits of gl_BaseInstance are unused
//Gives a relative position of +-255 relative to the player center in its respective lod
ivec3 extractRelativeLodPos() {
    return (ivec3(gl_BaseInstance)<<ivec3(3,12,21))>>ivec3(23);
}

vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255;
}

//Gets the face offset with respect to the face direction (e.g. some will be + some will be -)
float getFaceOffset(BlockModel model, uint face) {
    float offset = extractFaceIndentation(model.faceData[face]);
    return offset * (1-((int(face)&1)*2));
}

vec2 getFaceSizeOffset(BlockModel model, uint face, uint corner) {
    vec4 faceOffsetsSizes = extractFaceSizes(model.faceData[face]);
    return mix(faceOffsetsSizes.xz, -(1-faceOffsetsSizes.yw), bvec2(((corner>>1)&1)==1, (corner&1)==1));
}

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    int cornerIdx = gl_VertexID&3;
    Quad quad = quadData[uint(gl_VertexID)>>2];
    vec3 innerPos = extractPos(quad);
    uint face = extractFace(quad);
    uint modelId = extractStateId(quad);
    BlockModel model = modelData[modelId];

    //Change the ordering due to backface culling
    //NOTE: when rendering, backface culling is disabled as we simply dispatch calls for each face
    // this has the advantage of having "unassigned" geometry, that is geometry where the backface isnt culled
    //if (face == 0 || (face>>1 != 0 && (face&1)==1)) {
    //    cornerIdx ^= 1;
    //}

    uint lodLevel = extractLodLevel();
    ivec3 lodCorner = ((extractRelativeLodPos()<<lodLevel) - (baseSectionPos&(ivec3((1<<lodLevel)-1))))<<5;
    vec3 corner = innerPos * (1<<lodLevel) + lodCorner;

    vec2 faceOffset = getFaceSizeOffset(model, face, cornerIdx);
    vec2 quadSize = vec2(extractSize(quad) * ivec2((cornerIdx>>1)&1, cornerIdx&1));
    vec2 size = (quadSize + faceOffset) * (1<<lodLevel);

    vec3 offset = vec3(size, (float(face&1) + getFaceOffset(model, face)) * (1<<lodLevel));

    if ((face>>1) == 0) {//Up/down
        offset = offset.xzy;
    }
    //Not needed, here for readability
    //if ((face>>1) == 1) {//north/south
    //    offset = offset.xyz;
    //}
    if ((face>>1) == 2) {//west/east
        offset = offset.zxy;
    }

    gl_Position = MVP * vec4(corner + offset,1);


    //Compute the uv coordinates
    vec2 modelUV = vec2(modelId&0xFF, (modelId>>8)&0xFF)*(1f/(256f));
    //TODO: make the face orientated by 2x3 so that division is not a integer div and modulo isnt needed
    // as these are very slow ops
    baseUV = modelUV + (vec2(face%3, face/3) * (1f/(vec2(3,2)*256f)));
    uv = quadSize + faceOffset;//Add in the face offset for 0,0 uv

    discardAlpha = 0;

    //Compute lighting
    colourTinting = getLighting(extractLightId(quad));
    //Apply face tint
    if (face == 0) {
        colourTinting.xyz *= vec3(0.75, 0.75, 0.75);
    } else if (face != 1) {
        colourTinting.xyz *= vec3((float(face-2)/4)*0.6 + 0.4);
    }
}