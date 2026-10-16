using System;
using System.Collections.Generic;
using System.Linq;
namespace LanLink.Desktop {
 public struct SharedLayoutCell {
  public readonly int X,Y;
  public SharedLayoutCell(int x,int y){X=x;Y=y;}
  public SharedLayoutCell? Adjacent(string direction){int x=X+(direction=="left"?-1:direction=="right"?1:0),y=Y+(direction=="top"?-1:direction=="bottom"?1:0);return x>=-32&&x<=32&&y>=-32&&y<=32?(SharedLayoutCell?)new SharedLayoutCell(x,y):null;}
 }
 public sealed class SharedLayoutGeometry {
  public readonly double Width,Height,OriginX,OriginY,StepX,StepY,Scale;
  public readonly int MinX,MaxX,MinY,MaxY;
  public SharedLayoutGeometry(double width,double height,IEnumerable<SharedLayoutCell> cells){var list=cells.ToArray();MinX=Math.Max(-32,(list.Length==0?0:list.Min(p=>p.X))-1);MaxX=Math.Min(32,(list.Length==0?0:list.Max(p=>p.X))+1);MinY=Math.Max(-32,(list.Length==0?0:list.Min(p=>p.Y))-1);MaxY=Math.Min(32,(list.Length==0?0:list.Max(p=>p.Y))+1);int columns=MaxX-MinX+1,rows=MaxY-MinY+1;Scale=Math.Max(.65,Math.Min(1,Math.Min(Math.Max(1,width-32)/(columns*308),Math.Max(1,height-32)/(rows*222))));StepX=308*Scale;StepY=222*Scale;
   // Keep half a viewport around the outer grid cells so any device can be centered.
   Width=width+(columns-1)*StepX;Height=height+(rows-1)*StepY;OriginX=Width/2-(MinX+MaxX)*StepX/2;OriginY=Height/2-(MinY+MaxY)*StepY/2;}
  public double X(SharedLayoutCell cell){return OriginX+cell.X*StepX;}
  public double Y(SharedLayoutCell cell){return OriginY+cell.Y*StepY;}
  public SharedLayoutCell? Cell(double x,double y){int cx=(int)Math.Round((x-OriginX)/StepX,MidpointRounding.AwayFromZero),cy=(int)Math.Round((y-OriginY)/StepY,MidpointRounding.AwayFromZero);return cx>=-32&&cx<=32&&cy>=-32&&cy<=32?(SharedLayoutCell?)new SharedLayoutCell(cx,cy):null;}
 }
}
