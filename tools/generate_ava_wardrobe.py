#!/usr/bin/env python3
"""Generate skinned wardrobe variants for Ava from the existing Mixamo GLB.

Garment surfaces are extracted as separate meshes from the avatar, offset from
the body, thickened, and assigned the exact source vertex-group weights. The
evening dress gets a flared skirt silhouette. Inspect the exported GLBs before
shipping; this is a procedural first pass, not a hand-sculpted clothing pack.
"""
import argparse, sys
from pathlib import Path
import bpy
from mathutils import Vector

TORSO = ("mixamorig:Spine","mixamorig:Spine1","mixamorig:Spine2",
         "mixamorig:LeftShoulder","mixamorig:RightShoulder")
ARMS = ("mixamorig:LeftArm","mixamorig:LeftForeArm",
        "mixamorig:RightArm","mixamorig:RightForeArm")
LEGS = ("mixamorig:LeftUpLeg","mixamorig:LeftLeg",
        "mixamorig:RightUpLeg","mixamorig:RightLeg")
HIPS = ("mixamorig:Hips",)

OUTFITS = {
 "casual": [
  dict(name="Top",kind="top",y0=.49,y1=.88,torso=.14,arms=.48,offset=.008,
       color=(.10,.25,.39,1),rough=.72),
  dict(name="Trousers",kind="legs",y0=.05,y1=.59,legs=.16,hips=.28,offset=.009,
       color=(.075,.09,.13,1),rough=.82)],
 "sport": [
  dict(name="SportTop",kind="top",y0=.57,y1=.87,torso=.15,arms=.62,offset=.010,
       color=(.76,.075,.16,1),rough=.48),
  dict(name="Leggings",kind="legs",y0=.05,y1=.59,legs=.16,hips=.28,offset=.010,
       color=(.055,.075,.11,1),rough=.46)],
 "evening": [
  dict(name="EveningDress",kind="dress",y0=.10,y1=.89,torso=.10,arms=99.0,
       legs=.12,hips=.20,offset=.012,color=(.12,.035,.19,1),rough=.32,
       flare=.24,waist=.56,hem=.12)]
}

def args():
    argv=sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    p=argparse.ArgumentParser()
    p.add_argument("--input",required=True)
    p.add_argument("--output",required=True)
    return p.parse_args(argv)

def avatar():
    meshes=[o for o in bpy.context.scene.objects if o.type=="MESH"]
    if not meshes: raise RuntimeError("No mesh in input GLB")
    skinned=[o for o in meshes if any(m.type=="ARMATURE" and m.object for m in o.modifiers)]
    body=max(skinned or meshes,key=lambda o:len(o.data.polygons))
    mod=next((m for m in body.modifiers if m.type=="ARMATURE" and m.object),None)
    arm=mod.object if mod else (body.parent if body.parent and body.parent.type=="ARMATURE" else None)
    if arm is None: raise RuntimeError("Mixamo armature not found")
    return body,arm

def material(name,rgba,rough):
    m=bpy.data.materials.new(name); m.diffuse_color=rgba; m.use_nodes=True
    bs=m.node_tree.nodes.get("Principled BSDF")
    if bs:
        bs.inputs["Base Color"].default_value=rgba
        bs.inputs["Roughness"].default_value=rough
    return m

def wsum(v, group_ids, names):
    ids={group_ids[n] for n in names if n in group_ids}
    return sum(g.weight for g in v.groups if g.group in ids)

def make_garment(body,arm,outfit,cfg,group_ids):
    src=body.data
    min_y=min(v.co.y for v in src.vertices); max_y=max(v.co.y for v in src.vertices)
    height=max(max_y-min_y,1e-6)
    chosen=[]
    for p in src.polygons:
        vs=[src.vertices[i] for i in p.vertices]
        y=(sum(v.co.y for v in vs)/len(vs)-min_y)/height
        if not cfg["y0"]<=y<=cfg["y1"]: continue
        n=len(vs)
        tw=sum(wsum(v,group_ids,TORSO) for v in vs)/n
        aw=sum(wsum(v,group_ids,ARMS) for v in vs)/n
        lw=sum(wsum(v,group_ids,LEGS) for v in vs)/n
        hw=sum(wsum(v,group_ids,HIPS) for v in vs)/n
        if (tw>=cfg.get("torso",99) or aw>=cfg.get("arms",99)
            or lw>=cfg.get("legs",99) or hw>=cfg.get("hips",99)):
            chosen.append(p.index)
    if len(chosen)<30:
        raise RuntimeError(f"{outfit}/{cfg['name']}: only {len(chosen)} faces selected; check rig weights/bands")

    used=sorted({i for fi in chosen for i in src.polygons[fi].vertices})
    remap={old:new for new,old in enumerate(used)}
    cx=(min(v.co.x for v in src.vertices)+max(v.co.x for v in src.vertices))*.5
    cz=(min(v.co.z for v in src.vertices)+max(v.co.z for v in src.vertices))*.5
    coords=[]
    for old in used:
        v=src.vertices[old]; co=v.co.copy()
        normal=v.normal.normalized() if v.normal.length else Vector((0,0,1))
        co += normal*cfg["offset"]
        y=(v.co.y-min_y)/height
        if cfg["kind"]=="dress" and y<cfg["waist"]:
            progress=max(0,min(1,(cfg["waist"]-y)/max(cfg["waist"]-cfg["hem"],1e-5)))
            flare=1+cfg["flare"]*(progress**1.35)
            co.x=cx+(co.x-cx)*flare; co.z=cz+(co.z-cz)*flare
        elif cfg["kind"]=="legs":
            co.x=cx+(co.x-cx)*1.035; co.z=cz+(co.z-cz)*1.035
        coords.append(co)
    faces=[[remap[i] for i in src.polygons[fi].vertices] for fi in chosen]
    mesh=bpy.data.meshes.new(f"Ava_{outfit}_{cfg['name']}_Mesh")
    mesh.from_pydata(coords,[],faces); mesh.update()
    obj=bpy.data.objects.new(f"Ava_{outfit}_{cfg['name']}",mesh)
    bpy.context.collection.objects.link(obj)
    obj.matrix_world=body.matrix_world.copy()
    # Preserve exact skinning weights using original vertex indices.
    for g in body.vertex_groups: obj.vertex_groups.new(name=g.name)
    for old,new in remap.items():
        for assignment in src.vertices[old].groups:
            if assignment.weight>.0001:
                obj.vertex_groups[assignment.group].add([new],assignment.weight,"REPLACE")
    obj.parent=body.parent
    obj.matrix_parent_inverse=body.matrix_parent_inverse.copy()
    am=obj.modifiers.new("Ava_Mixamo_Skin","ARMATURE")
    am.object=arm; am.use_vertex_groups=True
    solid=obj.modifiers.new("Fabric_Thickness","SOLIDIFY")
    solid.thickness=.0035; solid.offset=0; solid.use_rim=True
    bevel=obj.modifiers.new("Soft_Fabric_Edges","BEVEL")
    bevel.width=.0012; bevel.segments=2
    for p in mesh.polygons: p.use_smooth=True
    mesh.materials.append(material(f"Ava_{outfit}_{cfg['name']}",cfg["color"],cfg["rough"]))
    return obj

def export_variant(body,arm,garments,path):
    bpy.ops.object.select_all(action="DESELECT")
    body.select_set(True); arm.select_set(True)
    for g in garments: g.select_set(True)
    bpy.context.view_layer.objects.active=body
    bpy.ops.export_scene.gltf(filepath=str(path),export_format="GLB",
        use_selection=True,export_apply=False,export_animations=True,
        export_skins=True,export_materials="EXPORT")

def main():
    a=args(); source=Path(a.input).resolve(); out=Path(a.output).resolve()
    if not source.is_file(): raise FileNotFoundError(source)
    out.mkdir(parents=True,exist_ok=True)
    bpy.ops.import_scene.gltf(filepath=str(source))
    body,arm=avatar()
    group_ids={g.name:g.index for g in body.vertex_groups}
    if not any(n in group_ids for n in TORSO+ARMS+LEGS+HIPS):
        raise RuntimeError("Expected Mixamo vertex groups are missing")
    print(f"Base={body.name}; vertices={len(body.data.vertices)}; faces={len(body.data.polygons)}; armature={arm.name}")
    for name,parts in OUTFITS.items():
        garments=[]
        try:
            for cfg in parts: garments.append(make_garment(body,arm,name,cfg,group_ids))
            path=out/f"ava_{name}.glb"
            export_variant(body,arm,garments,path)
            print(f"EXPORTED {path} ({path.stat().st_size} bytes)")
        finally:
            for g in garments: bpy.data.objects.remove(g,do_unlink=True)
    for name in OUTFITS:
        p=out/f"ava_{name}.glb"
        if not p.is_file() or p.stat().st_size<10000: raise RuntimeError(f"Missing/invalid output: {p}")
    print("WARDROBE_GENERATION_OK")

if __name__=="__main__": main()
