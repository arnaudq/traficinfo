The ds3mfunction downloads the csv and geojson files from

and store them in a bucket named ds3mbucket

fn list context 
fn list apps

fn -v deploy --app 3MOpenDataProcessor

fn config function 3MOpenDataProcessor 3mopendatasource NAMESPACE frivwhhfdcjn
fn config function 3MOpenDataProcessor 3mopendatasource BUCKETNAME ds3mbucket


fn invoke 3MOpenDataProcessor 3mopendatasource

OCI Dynamic Groups
traficinfo_fn: all Functions in traficinfo compartment
traficinfo_apigw: all APIGW in traficinfo compartment

Policies
grant-fn-access-to-use-objects: "Allow dynamic-group traficinfo_fn to use objects in compartment TraficInfo"