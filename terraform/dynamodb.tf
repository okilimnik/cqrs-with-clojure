module "dynamodb-table" {
  source  = "terraform-aws-modules/dynamodb-table/aws"
  version = "4.2.0"

  name         = var.table_name
  attributes   = var.table_attributes
  hash_key     = var.table_hash_key
  billing_mode = "PAY_PER_REQUEST"
}