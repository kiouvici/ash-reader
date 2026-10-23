# SPDX-License-Identifier: AGPL-3.0-or-later
# Word 원본 보기(F-30) 시각 대조: 우리 PDF 와 기준 PDF(Word 출력)를 같은 해상도로 렌더해 쪽마다 비교한다.
#   python3 compare.py ours.pdf ref.pdf [dpi=40] [sheet.png] [쪽수]
# 지표: ssim = 흐린 회색조 SSIM, lineIoU = 가로로 크게 팽창한 잉크(줄·문단·표 배치) IoU, vcorr = 세로 잉크 분포 상관.
# 실무 문서 그림은 저장소에 올리지 않는다(sheet.png 는 로컬 확인용).
import sys, numpy as np, pypdfium2 as pdfium
from PIL import Image
from skimage.metrics import structural_similarity as ssim
import cv2
def render(path, dpi):
    d = pdfium.PdfDocument(path); out=[]
    for i in range(len(d)):
        im = d[i].render(scale=dpi/72).to_pil().convert('L'); out.append(im)
    return out
ours, ref = sys.argv[1], sys.argv[2]; dpi=float(sys.argv[3]) if len(sys.argv)>3 else 40
sheet = sys.argv[4] if len(sys.argv)>4 else None
A=render(ours,dpi); B=render(ref,dpi)
n=min(len(A),len(B)); rows=[]
for i in range(n):
    a=np.array(A[i]); b=np.array(B[i].resize(A[i].size))
    # 1) 흐림 SSIM  2) 잉크 IoU(팽창 3px)
    ab=cv2.GaussianBlur(a,(0,0),2); bb=cv2.GaussianBlur(b,(0,0),2)
    s=ssim(ab,bb,data_range=255)
    ia=(a<200).astype(np.uint8); ib=(b<200).astype(np.uint8)
    # 줄 띠 IoU: 가로로 크게 팽창해 낱자 위치 대신 줄·문단·표 배치를 본다
    k=cv2.getStructuringElement(cv2.MORPH_RECT,(int(dpi*0.6)|1,int(dpi*0.06)|1)); da=cv2.dilate(ia,k); db=cv2.dilate(ib,k)
    inter=(da&db).sum(); uni=(da|db).sum(); iou=inter/uni if uni else 1.0
    # 3) 잉크 세로 분포 상관(줄 위치)
    pa=ia.sum(1).astype(float); pb=ib.sum(1).astype(float)
    pa=cv2.GaussianBlur(pa.reshape(-1,1),(0,0),3).ravel(); pb=cv2.GaussianBlur(pb.reshape(-1,1),(0,0),3).ravel()
    corr=float(np.corrcoef(pa,pb)[0,1]) if pa.std()>0 and pb.std()>0 else 0.0
    rows.append((i+1,s,iou,corr))
print(f"pages ours={len(A)} ref={len(B)}")
for r in rows: print("page %d ssim=%.3f lineIoU=%.3f vcorr=%.3f"%r)
if rows:
    m=np.mean([r[1] for r in rows]),np.mean([r[2] for r in rows]),np.mean([r[3] for r in rows])
    print("MEAN ssim=%.3f lineIoU=%.3f vcorr=%.3f"%m)
if sheet:
    k=min(n, int(sys.argv[5]) if len(sys.argv)>5 else 4); w=A[0].size[0]; h=A[0].size[1]
    sh=Image.new('L',(w*2+10,(h+10)*k),255)
    for i in range(k):
        sh.paste(A[i],(0,i*(h+10))); sh.paste(B[i].resize(A[i].size),(w+10,i*(h+10)))
    sh.save(sheet)
