double seriesConductance(double area,double first,double firstDistance,double second,double secondDistance){
    return area*first*second/(firstDistance*second+secondDistance*first);
}
