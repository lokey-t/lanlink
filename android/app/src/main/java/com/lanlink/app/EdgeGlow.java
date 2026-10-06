package com.lanlink.app;

import android.animation.ValueAnimator;
import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.*;
import android.provider.Settings;
import android.view.*;

/** Optional visual feedback only; never captures input or requests permission on its own. */
final class EdgeGlow extends View {
  private final WindowManager windows;
  private final String edge;
  private ValueAnimator animation;
  private boolean attached;
  private float strength;
  private boolean counting;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  EdgeGlow(Context context,String edge){super(context);this.edge=edge;windows=context.getSystemService(WindowManager.class);}
  static EdgeGlow feedback(Context context,EdgeGlow current,String phase,String edge,double progress){
    if(phase.equals("clear")||phase.equals("cancel")){if(current!=null)current.close();return null;}
    if(!context.getSharedPreferences("identity",0).getBoolean("edge_glow",false) || !Settings.canDrawOverlays(context)){if(current!=null)current.close();return null;}
    if(!java.util.Arrays.asList("left","right","top","bottom").contains(edge))return null;
    if(!phase.equals("countdown") && !phase.equals("depart"))return current;
    EdgeGlow view=current;
    if(view==null || !view.attached || !view.edge.equals(edge) || phase.equals("countdown")&&!view.counting){if(view!=null)view.close();view=attach(context,edge);}
    if(view==null)return null;
    view.stopAnimation();view.counting=phase.equals("countdown");
    if(view.counting){view.strength=.5f*(float)Math.max(0,Math.min(1,progress));view.invalidate();return view;}
    view.strength=.5f;view.invalidate();final EdgeGlow panel=view;
    PropertyValuesHolder pulse=PropertyValuesHolder.ofKeyframe("strength",Keyframe.ofFloat(0,.5f),Keyframe.ofFloat(100f/340,1),Keyframe.ofFloat(1,0));
    view.animation=ValueAnimator.ofPropertyValuesHolder(pulse);view.animation.setInterpolator(new android.view.animation.LinearInterpolator());view.animation.setDuration(340);
    view.animation.addUpdateListener(a->{panel.strength=(float)a.getAnimatedValue("strength");panel.invalidate();});
    view.animation.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator a){panel.close();}});view.animation.start();return view;
  }
  private static EdgeGlow attach(Context context,String edge){
    EdgeGlow view=new EdgeGlow(context,edge);
    boolean vertical=edge.equals("left")||edge.equals("right");int strip=(int)(24*context.getResources().getDisplayMetrics().density);
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(vertical?strip:-1,vertical?-1:strip,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    p.gravity=edge.equals("left")?Gravity.LEFT|Gravity.TOP:edge.equals("right")?Gravity.RIGHT|Gravity.TOP:edge.equals("top")?Gravity.TOP|Gravity.LEFT:Gravity.BOTTOM|Gravity.LEFT;
    try{view.windows.addView(view,p);view.attached=true;return view;}catch(RuntimeException e){view.close();return null;}
  }
  protected void onDraw(Canvas canvas){boolean vertical=edge.equals("left")||edge.equals("right"),reverse=edge.equals("right")||edge.equals("bottom");float length=vertical?getWidth():getHeight();paint.setShader(new LinearGradient(vertical?(reverse?length:0):0,vertical?0:(reverse?length:0),vertical?(reverse?0:length):0,vertical?0:(reverse?0:length),new int[]{0xffa78bfa,0x806cb7ff,0x006cb7ff},new float[]{0,.3f,1},Shader.TileMode.CLAMP));paint.setAlpha((int)(255*strength));canvas.drawRect(0,0,getWidth(),getHeight(),paint);}
  private void stopAnimation(){if(animation!=null){animation.removeAllListeners();animation.removeAllUpdateListeners();animation.cancel();animation=null;}}
  void close(){stopAnimation();if(attached){attached=false;try{windows.removeView(this);}catch(RuntimeException ignored){}}}
}
