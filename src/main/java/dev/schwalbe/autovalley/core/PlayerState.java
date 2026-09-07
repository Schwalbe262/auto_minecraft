package dev.schwalbe.autovalley.core;
public record PlayerState(double x,double y,double z,float yaw,float pitch,boolean onGround,boolean sleeping,float health,int food,int selectedSlot,boolean connected,boolean focused) {
    public Pos feet() { return new Pos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)); }
    public double distance(Pos p) { return Math.sqrt(Math.pow(x-p.x()-0.5,2)+Math.pow(y-p.y(),2)+Math.pow(z-p.z()-0.5,2)); }
}
