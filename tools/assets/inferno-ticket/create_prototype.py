"""Run with the installed Blender: --background --factory-startup --python this_file.

Creates an editable prototype and an RS-aware GLB without touching any packed cache.
Blender Z-up maps to RS negative Y-up; Blender Y maps to RS Z.
"""
import bpy
import colorsys
import json
import math
import struct
from pathlib import Path
from mathutils import Vector

OUT = Path(__file__).resolve().parent
PALETTE = [(0.012, 0.005, 0.008, 1), (0.85, 0.32, 0.012, 1),
           (0.30, 0.055, 0.004, 1), (0.95, 0.105, 0.003, 1),
           (1.0, 0.64, 0.055, 1), (0.08, 0.008, 0.009, 1)]
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
mats = []
for name, color in zip(['Obsidian', 'Gold', 'Dark gold', 'Lava', 'Gold highlight', 'Ember'], PALETTE):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = color
    mat.use_nodes = True
    shader = mat.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Base Color'].default_value = color
    shader.inputs['Roughness'].default_value = 0.65
    mats.append(mat)

# Outline with an inward semicircular notch at each short end.
outline = [(-100, -48), (100, -48), (100, -18)]
for i in range(1, 9):
    a = -math.pi / 2 - i * math.pi / 8
    outline.append((100 + 18 * math.cos(a), 18 * math.sin(a)))
outline += [(100, 48), (-100, 48), (-100, 18)]
for i in range(1, 9):
    a = math.pi / 2 - i * math.pi / 8
    outline.append((-100 + 18 * math.cos(a), 18 * math.sin(a)))

vertices, faces, material_ids = [], [], []
def vert(x, y, z):
    vertices.append((round(x), round(y), round(z)))
    return len(vertices) - 1
def face(ids, mat):
    faces.append(tuple(ids)); material_ids.append(mat)

n = len(outline)
bottom = [vert(x, y, -2) for x, y in outline]
outer = [vert(x, y, 2) for x, y in outline]
inner = [vert(x * 0.95, y * 0.91, 3) for x, y in outline]
center = vert(0, 0, 2)
back = vert(0, 0, -2)
for i in range(n):
    j = (i + 1) % n
    face([bottom[i], bottom[j], outer[j], outer[i]], 2)
    face([outer[i], outer[j], inner[j], inner[i]], 4 if i % 3 else 1)
    face([center, inner[i], inner[j]], 0)
    face([back, bottom[j], bottom[i]], 2)

def strip(points, width, material, z=5):
    closed = points[0] == points[-1]
    points = points[:-1] if closed else points
    normals=[]
    for i in range(len(points) if closed else len(points)-1):
        x1,y1=points[i]; x2,y2=points[(i+1)%len(points)]
        length=math.hypot(x2-x1,y2-y1)
        normals.append((-(y2-y1)/length,(x2-x1)/length))
    sides=[]
    for i,(x,y) in enumerate(points):
        before=normals[(i-1)%len(normals)] if closed or i>0 else normals[0]
        after=normals[i%len(normals)] if closed or i<len(points)-1 else normals[-1]
        nx,ny=before[0]+after[0],before[1]+after[1]
        length=math.hypot(nx,ny)
        if length<0.01: nx,ny=after
        else: nx,ny=nx/length,ny/length
        scale=min(width,width/2/max(0.5,nx*after[0]+ny*after[1]))
        sides.append((vert(x+nx*scale,y+ny*scale,z),vert(x-nx*scale,y-ny*scale,z)))
    for i in range(len(points) if closed else len(points)-1):
        j=(i+1)%len(points)
        face([sides[i][0],sides[i][1],sides[j][1],sides[j][0]],material)

def polygon(points, mat, z=4):
    face([vert(x,y,z) for x,y in points], mat)

# Inset double gold line, with angular scrollwork at the four corners.
for scale, width, mat in [(0.91, 2, 2), (0.88, 2, 1)]:
    ring = [(x*scale, y*scale) for x,y in outline]
    strip(ring+[ring[0]], width, mat, 4)
for sx in [-1,1]:
    for sy in [-1,1]:
        strip([(sx*87,sy*30),(sx*80,sy*30),(sx*77,sy*37),(sx*65,sy*37)],2,4,5)

# Stylised flames around the cape: each is a silhouette with a smaller hot core.
flames = [
 [(-79,-31),(-77,-17),(-69,-8),(-73,-22),(-66,-17),(-69,-32)],
 [(-58,-36),(-50,-23),(-51,-12),(-45,-23),(-40,-19),(-43,-36)],
 [(-11,-35),(0,-25),(4,-12),(9,-22),(20,-11),(17,-27),(7,-36)],
 [(11,10),(7,21),(15,30),(11,39),(23,31),(20,22),(28,25),(24,13)],
 [(-61,19),(-55,27),(-50,39),(-46,29),(-40,33),(-44,20)],
 [(58,27),(62,33),(60,40),(69,35),(71,25),(65,22)],
 [(58,-35),(61,-23),(68,-17),(65,-30),(74,-27),(70,-37)]
]
for flame in flames:
    polygon(flame,3,4)
    cx=sum(p[0] for p in flame)/len(flame); cy=sum(p[1] for p in flame)/len(flame)
    polygon([(cx+(x-cx)*0.48,cy+(y-cy)*0.65) for x,y in flame],1,5)

# Infernal cape emblem, filled with dark hexagonal plates separated by molten seams.
cape = [(-54,-32),(-59,-19),(-50,-2),(-39,16),(-28,31),(-13,37),
        (-2,34),(0,28),(-7,24),(-13,27),(-18,22),(-17,16),(-10,13),
        (-4,16),(0,11),(-5,3),(-3,-10),(-8,-26),(-23,-34),(-39,-37)]
polygon(cape,3,6)
strip(cape+[cape[0]],2,4,7)
def inside(x,y,poly):
    result=False
    j=len(poly)-1
    for i in range(len(poly)):
        xi,yi=poly[i]; xj,yj=poly[j]
        if (yi>y)!=(yj>y) and x < (xj-xi)*(y-yi)/(yj-yi)+xi: result=not result
        j=i
    return result
for row in range(9):
    for col in range(8):
        cx=-57+col*12+(row%2)*6; cy=-35+row*10.5
        hexagon=[(cx+6*math.cos(math.pi/3*k+math.pi/6),cy+6*math.sin(math.pi/3*k+math.pi/6)) for k in range(6)]
        if all(inside(x,y,cape) for x,y in hexagon):
            polygon(hexagon,0 if (row+col)%3 else 5,7)

# Crown and laurel stamp in the left field.
polygon([(-81,22),(-84,32),(-78,28),(-75,36),(-71,28),(-66,32),(-68,22)],4,6)
strip([(-80,19),(-69,19)],2,1,6)
for sx in [-1,1]:
    stem=[(-75+sx*12,11),(-75+sx*16,16),(-75+sx*18,23)]
    strip(stem,2,1,6)
    for x,y in stem[1:]:
        polygon([(x,y),(x+sx*3,y+5),(x-sx*2,y+3)],4,7)

# Perforation and gift seal, kept clear of the short-edge notch.
for y in range(-32, 34, 11): strip([(39,y),(39,y+5)],2,4,5)
polygon([(56,-12),(76,-12),(76,4),(56,4)],1,6)
polygon([(55,4),(77,4),(77,9),(55,9)],4,6)
strip([(66,-13),(66,9)],3,4,7)
strip([(65,9),(57,14),(56,18),(60,19),(66,12),(72,19),(76,18),(75,14),(67,9)],2,4,7)

mesh = bpy.data.meshes.new('InfernoTicketPrototypeMesh')
mesh.from_pydata(vertices, [], faces)
mesh.materials.clear()
for mat in mats: mesh.materials.append(mat)
for poly, mat in zip(mesh.polygons, material_ids): poly.material_index = mat
mesh.update()
obj = bpy.data.objects.new('Inferno ticket - lava cape artwork', mesh)
bpy.context.collection.objects.link(obj)
bpy.context.view_layer.objects.active = obj
obj.select_set(True)
obj['purpose'] = 'Artwork candidate; not yet tested in the game client or allocated in cache'
mesh.calc_loop_triangles()

# Quantization can collapse very small decorative triangles. Remove them in both
# the editable mesh and the export so the Blender preview matches RS geometry.
triangles, triangle_mats = [], []
for tri in mesh.loop_triangles:
    a,b,c = [mesh.vertices[i].co for i in tri.vertices]
    if (b-a).cross(c-a).length_squared > 0:
        triangles.append(tuple(tri.vertices))
        triangle_mats.append(mesh.polygons[tri.polygon_index].material_index)
clean = bpy.data.meshes.new('InfernoTicketTriangulated')
clean.from_pydata([tuple(v.co) for v in mesh.vertices],[],triangles)
for mat in mats: clean.materials.append(mat)
for poly,mat in zip(clean.polygons,triangle_mats): poly.material_index=mat
obj.data=clean
mesh=clean
mesh.calc_loop_triangles()

def rs_hsl(rgb):
    h, l, s = colorsys.rgb_to_hls(*rgb[:3])
    return (min(63,int(h*64)) << 10) | (min(7,int(s*8)) << 7) | min(127,max(2,int(l*128)))

positions = [(round(v.co.x), round(-v.co.z), round(v.co.y)) for v in mesh.vertices]
indices, colours = [], []
for tri in mesh.loop_triangles:
    indices.extend(tri.vertices)
    colours.append(rs_hsl(PALETTE[mesh.polygons[tri.polygon_index].material_index]))
binary = b''.join(struct.pack('<3f', *p) for p in positions)
index_offset = len(binary)
binary += struct.pack('<'+'H'*len(indices), *indices)
root = {
 'asset': {'version':'2.0','generator':'Drynor Blender ticket prototype'},
 'scene':0,'scenes':[{'nodes':[0]}],
 'nodes':[{'mesh':0,'extras':{'rs':{'format':'new','version':15,
    'footer':{'flags':8,'priority':0,'alphas':0,'faceSkins':0,'materials':0,'vertexSkins':0},
    'colours':colours}}}],
 'meshes':[{'primitives':[{'attributes':{'POSITION':0},'indices':1,'mode':4}]}],
 'buffers':[{'byteLength':len(binary)}],
 'bufferViews':[{'buffer':0,'byteOffset':0,'byteLength':index_offset},
                {'buffer':0,'byteOffset':index_offset,'byteLength':len(indices)*2}],
 'accessors':[{'bufferView':0,'componentType':5126,'count':len(positions),'type':'VEC3',
    'min':[min(p[i] for p in positions) for i in range(3)],
    'max':[max(p[i] for p in positions) for i in range(3)]},
    {'bufferView':1,'componentType':5123,'count':len(indices),'type':'SCALAR'}]
}
json_bytes = json.dumps(root,separators=(',',':')).encode()
json_bytes += b' ' * (-len(json_bytes)%4)
binary += b'\0' * (-len(binary)%4)
glb = struct.pack('<III',0x46546c67,2,12+8+len(json_bytes)+8+len(binary))
glb += struct.pack('<II',len(json_bytes),0x4e4f534a)+json_bytes
glb += struct.pack('<II',len(binary),0x004e4942)+binary
(OUT/'inferno-ticket-rs.glb').write_bytes(glb)

# Native Blender export is a visual reference, separate from the RS-aware conversion input.
bpy.ops.export_scene.gltf(filepath=str(OUT/'inferno-ticket-visual.glb'),use_selection=True,export_format='GLB')
scene = bpy.context.scene
scene.world.color = (0.12,0.12,0.12)
camera_data = bpy.data.cameras.new('PreviewCamera')
camera = bpy.data.objects.new('PreviewCamera',camera_data)
bpy.context.collection.objects.link(camera)
camera.location = (0,-95,350)
camera.rotation_euler = (Vector((0,0,0))-camera.location).to_track_quat('-Z','Y').to_euler()
camera.rotation_euler.rotate_axis('Z',-0.30)
camera_data.type='ORTHO'; camera_data.ortho_scale=270
scene.camera=camera
for name, location, energy, size in [('Key',(-100,-100,220),450000,180),('Fill',(100,100,150),180000,130)]:
    light_data=bpy.data.lights.new(name,'AREA'); light_data.energy=energy; light_data.shape='DISK'; light_data.size=size
    light=bpy.data.objects.new(name,light_data); bpy.context.collection.objects.link(light)
    light.location=location; light.rotation_euler=(-light.location).to_track_quat('-Z','Y').to_euler()
scene.render.engine='CYCLES'; scene.cycles.samples=24
scene.render.resolution_x=800;scene.render.resolution_y=500;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.filepath=str(OUT/'prototype-preview.png')
scene.view_settings.view_transform='Standard'
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'inferno-ticket.blend'))
bpy.ops.render.render(write_still=True)
print('DRYNOR_PROTOTYPE '+json.dumps({'vertices':len(positions),'faces':len(colours),'rs_glb_bytes':len(glb)}))
