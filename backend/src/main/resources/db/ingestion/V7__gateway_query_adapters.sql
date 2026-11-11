INSERT INTO collection_endpoint(id,name,method,path,items_pointer,request_template,review_status,documentation_url,platform_code,success_code) VALUES
 ('gateway.factory-stock','协同：查询工厂库存','POST','/supply-service/web/factoryInventory/page','/data/list','{"pageNum":1,"pageSize":5}',
 'APPROVED','http://gateway-test.emigo.it/supply-service/doc.html','migo-supply',200),
 ('gateway.fba-stock','物流：查询FBA库存明细','POST','/logistics-service/web/data/biFbaInventory/pageList','/data/list','{"pageNum":1,"pageSize":5}',
 'APPROVED','http://gateway-test.emigo.it/logistics-service/doc.html','migo-tms',200);
ALTER TABLE collection_profile ADD COLUMN token_expires_at timestamptz;
