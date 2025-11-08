variable "table_name" {
  type        = string
  description = "Name of the DynamoDB table"
  default     = "table"
}

variable "table_attributes" {
  type        = list(map(string))
  description = "Attributes of the DynamoDB table"
  default = [
    {
      name = "UserId",
      type = "S",
    },
  ]
}

variable "table_hash_key" {
  type        = string
  description = "Hash key of the DynamodDB table"
  default     = "UserId"
}