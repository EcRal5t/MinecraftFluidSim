double mixturePressure(double capacity,double liquid,double gasProduct,double bulk,double reference,out double norm){
    double limit=bulk+reference,b=capacity*bulk+gasProduct-limit*liquid;
    double cross=2.0LF*sqrt(liquid)*sqrt(limit*gasProduct),magnitude=max(abs(b),cross);
    norm=magnitude>0.0LF?magnitude*sqrt((b/magnitude)*(b/magnitude)+(cross/magnitude)*(cross/magnitude)):0.0LF;
    if(gasProduct==0.0LF)return liquid==0.0LF||b>=0.0LF?0.0LF:-b/liquid;
    if(liquid==0.0LF)return limit*gasProduct/(capacity*bulk+gasProduct);
    return b>=0.0LF?2.0LF*limit*gasProduct/(b+norm):(norm-b)/(2.0LF*liquid);
}
