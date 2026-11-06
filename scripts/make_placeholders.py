from PIL import Image, ImageDraw
import os
os.makedirs("branding", exist_ok=True)
def grad(w,h):
    im=Image.new("RGB",(w,h))
    d=ImageDraw.Draw(im)
    for y in range(h):
        t=y/h; d.line([(0,y),(w,y)],fill=(int(90-50*t),int(40+10*t),int(160-60*t)))
    return im
i=grad(512,512); d=ImageDraw.Draw(i)
d.ellipse((190,300,290,380),fill="white"); d.rectangle((270,130,292,340),fill="white"); d.polygon([(270,130),(370,170),(370,210),(270,175)],fill="white")
i.save("branding/icon.jpg",quality=92)
s=grad(1080,1920); d=ImageDraw.Draw(s)
d.ellipse((440,900,540,980),fill="white"); d.rectangle((520,730,542,940),fill="white"); d.polygon([(520,730),(620,770),(620,810),(520,775)],fill="white")
s.save("branding/splash.jpg",quality=90)
