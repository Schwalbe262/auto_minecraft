package dev.schwalbe.autovalley.core;
public record Farm(String name, Pos first, Pos second, String cropId) {
    public Farm {
        // Old JSON records and the existing registration API describe tomato fields.
        cropId=cropId==null || cropId.isBlank() ? CropRules.TOMATO : cropId;
    }
    public Farm(String name,Pos first,Pos second) {this(name,first,second,CropRules.TOMATO);}
    public boolean contains(Pos p) {
        return p.x() >= Math.min(first.x(),second.x()) && p.x() <= Math.max(first.x(),second.x())
            && p.y() >= Math.min(first.y(),second.y()) && p.y() <= Math.max(first.y(),second.y())
            && p.z() >= Math.min(first.z(),second.z()) && p.z() <= Math.max(first.z(),second.z());
    }
    public long volume() {
        long width=1+Math.abs((long)first.x()-second.x()),height=1+Math.abs((long)first.y()-second.y()),depth=1+Math.abs((long)first.z()-second.z());
        try {return Math.multiplyExact(Math.multiplyExact(width,height),depth);}
        catch(ArithmeticException overflow) {return Long.MAX_VALUE;}
    }
}
