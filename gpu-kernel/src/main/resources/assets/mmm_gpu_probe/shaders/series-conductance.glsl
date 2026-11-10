double seriesConductance(double area,double first,double firstDistance,double second,double secondDistance){
    if(first==0.0LF || second==0.0LF)return 0.0LF;
    return area*first*second/(firstDistance*second+secondDistance*first);
}
